package com.lark.oapi.sample.ws.service;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Office 文档解析（11-23-kb-office-documents）：
 * <ul>
 *   <li>docx / pptx / xlsx：<b>零第三方依赖</b>（java.util.zip + StAX），按 OOXML 规范抽取正文转 Markdown。
 *       docx 跳过 w:del（修订删除）与 w:instrText（域代码）；pptx 幻灯片按数值序（slide2 在 slide10 前）；
 *       xlsx 最多 {@value #MAX_SHEETS} 个工作表转 markdown 表。</li>
 *   <li>doc（Word 97-2003 / OLE2）：Apache POI HWPF（poi-scratchpad），段落 + 尽力保留表格行列结构；
 *       抽不出表格时降级线性文本并标记 {@code tablesLost}；失败分类（加密/损坏等）走 {@link DocParseException}（O2b）。</li>
 *   <li>格式判定一律按 magic bytes（ZIP=PK / OLE2=D0CF11E0），不信任扩展名；扩展名与真实字节不符时
 *       {@link #resolveOfficeFile} 返回真实类型并标记 mislabeled（AC2b）。</li>
 *   <li>zip 安全：entry 数与声明解压大小预检 + 解压流实时计数硬上限（防 zip bomb）；
 *       非 XML 内容（加密 entry/损坏）给可读错误；产物字符受 {@link #MAX_MD_CHARS} 预算截断（O4 体积纪律）。</li>
 * </ul>
 * 纯静态、可单测；不依赖 Spring。
 */
public final class SupplierOfficeParser {

    /** 入库文件大小上限（与 PDF 一致） */
    public static final long MAX_INPUT_BYTES = 50L * 1024 * 1024;
    /** O5 诚实阈值：docx/pptx/doc 抽取正文低于该字数视为图片型文档（不许假装成功） */
    public static final int MIN_TEXT_CHARS = 200;
    /** xlsx 最多转换的工作表数（超出仅在产物里列名） */
    static final int MAX_SHEETS = 5;
    /** 产物 Markdown 字符预算：必须明显低于 SupplierKbService.contextText 的 120k 共享预算（O4） */
    static final int MAX_MD_CHARS = 90_000;
    static final long MAX_TOTAL_UNCOMPRESSED = 256L * 1024 * 1024;   // zip 全部 entry 解压总量上限
    static final long MAX_ENTRY_UNCOMPRESSED = 96L * 1024 * 1024;    // 单 entry 解压上限
    static final int MAX_ZIP_ENTRIES = 20_000;                       // entry 数上限（防目录炸弹）
    private static final int MAX_CELL_CHARS = 200;                   // xlsx/表格单元格显示截断

    private static final String NS_DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*0*([1-9])");
    private static final Pattern SLIDE_PATH = Pattern.compile("ppt/slides/slide(\\d+)\\.xml", Pattern.CASE_INSENSITIVE);

    private SupplierOfficeParser() {}

    /** 解析产物 + 诚实统计（不适用维度为 -1） */
    public record Parsed(String markdown, int extractedChars, boolean truncated,
                         int rows, int cols, int sheets, int slides, boolean tablesLost) {}

    /** .doc 解析失败分类（O2b：加密/损坏/老式ppt/xls 等，入库时均不得产生 KB 条目） */
    public static class DocParseException extends IOException {
        public final String kind;   // encrypted | corrupt | legacy-ppt | legacy-xls | unsupported-ole2
        DocParseException(String kind, String message) { super(message); this.kind = kind; }
    }

    public enum Magic { ZIP, OLE2, UNKNOWN }

    /** 安全上限组（测试可传小值快速触发截断/炸弹分支） */
    record Limits(long maxTotal, long maxEntry, int maxMdChars) {}
    static final Limits DEFAULTS = new Limits(MAX_TOTAL_UNCOMPRESSED, MAX_ENTRY_UNCOMPRESSED, MAX_MD_CHARS);

    /** resolveOfficeFile 结果：按真实字节判定的有效类型 + 扩展名是否名不符实 */
    public record Resolution(String effectiveType, boolean mislabeled) {}

    // ===== magic bytes 判定（不信扩展名） =====

    public static Magic sniff(Path file) throws IOException {
        byte[] head = new byte[4];
        int n;
        try (InputStream in = Files.newInputStream(file)) { n = in.readNBytes(head, 0, 4); }
        if (n >= 4 && head[0] == 0x50 && head[1] == 0x4B
                && (head[2] == 3 || head[2] == 5 || head[2] == 7)) return Magic.ZIP;
        if (n >= 4 && head[0] == (byte) 0xD0 && head[1] == (byte) 0xCF
                && head[2] == 0x11 && head[3] == (byte) 0xE0) return Magic.OLE2;
        return Magic.UNKNOWN;
    }

    /**
     * 入库/建档预检：按 magic bytes 判定真实格式并返回有效类型（AC2b）。
     * OLE2 内部分类靠 POIFS 根目录流名；不可解析形态（加密/老式 .ppt/.xls/损坏/非 Office）抛可读 IllegalArgumentException。
     * 注意：本方法只做<b>结构级</b>判定（廉价），.doc 正文级失败（空正文/HWPF 深层损坏）由 {@link #docToMarkdown} 分类抛出。
     */
    public static Resolution resolveOfficeFile(Path file, String declaredType) {
        Magic magic;
        try {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Office 文件不存在: " + file);
            long size = Files.size(file);
            if (size == 0) throw new IllegalArgumentException("Office 文件为空（0 字节），无法解析: " + file);
            if (size > MAX_INPUT_BYTES)
                throw new IllegalArgumentException("Office 文件超过 " + (MAX_INPUT_BYTES / 1024 / 1024) + "MB 上限: " + size);
            magic = sniff(file);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Office 文件不可读: " + ex.getMessage(), ex);
        }
        String name = file.getFileName().toString();
        return switch (magic) {
            case ZIP -> {
                String real = ooxmlKind(file);
                if (real == null) throw new IllegalArgumentException(
                        "不是可识别的 OOXML 文档（缺少 word/document.xml、ppt/presentation.xml、xl/workbook.xml 结构），可能已损坏: " + name);
                yield new Resolution(real, !real.equals(declaredType));
            }
            case OLE2 -> switch (ole2Kind(file)) {
                case "word" -> new Resolution("doc", !"doc".equals(declaredType));
                case "encrypted" -> throw new IllegalArgumentException(
                        "这是带密码保护的 Office 文件（加密 OLE2 包），无法解析。请在 Office 里移除密码，或「另存为 .docx/.pptx/.xlsx」后重新入库: " + name);
                case "ppt" -> throw new IllegalArgumentException(
                        "这是老式 PowerPoint .ppt（OLE2 复合文档），当前不解析。请在 PowerPoint 里「另存为 .pptx」后重新入库: " + name);
                case "xls" -> throw new IllegalArgumentException(
                        "这是老式 Excel .xls（OLE2 复合文档），当前不解析。请在 Excel 里「另存为 .xlsx」后重新入库: " + name);
                case "corrupt" -> throw new IllegalArgumentException(
                        "OLE2 复合文档结构损坏，无法读取" + DOC_NEXT_STEPS + ": " + name);
                default -> throw new IllegalArgumentException(
                        "OLE2 复合文档但未找到 Word/PowerPoint/Excel 正文流（可能是其他软件的旧式数据文件），无法解析: " + name);
            };
            case UNKNOWN -> throw new IllegalArgumentException(
                    "不是有效的 Office 文件（文件头既非 ZIP 也非 OLE2，可能已损坏或根本不是 Office 文档）: " + name);
        };
    }

    /** OLE2 内部种类：word / ppt / xls / encrypted / unknown（读不动 → corrupt） */
    static String ole2Kind(Path file) {
        try (POIFSFileSystem fs = new POIFSFileSystem(file.toFile(), true)) {
            DirectoryNode root = fs.getRoot();
            if (root.hasEntry("EncryptedPackage") || root.hasEntry("EncryptionInfo")) return "encrypted";
            if (root.hasEntry("WordDocument")) return "word";
            if (root.hasEntry("PowerPoint Document")) return "ppt";
            if (root.hasEntry("Workbook")) return "xls";
            return "unknown";
        } catch (IOException | RuntimeException ex) {
            return "corrupt";
        }
    }

    /** ZIP(OOXML) 内部种类：docx / pptx / xlsx / null（不认识） */
    static String ooxmlKind(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (findEntry(zip, "word/document.xml") != null) return "docx";
            if (findEntry(zip, "ppt/presentation.xml") != null) return "pptx";
            if (findEntry(zip, "xl/workbook.xml") != null) return "xlsx";
            return null;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    // ===== 解析入口 =====

    /** 按有效类型解析为 Markdown（调用方先经 {@link #resolveOfficeFile} 判定真实类型） */
    public static Parsed parse(Path file, String type) throws IOException {
        return parse(file, type, DEFAULTS);
    }

    static Parsed parse(Path file, String type, Limits lim) throws IOException {
        return switch (type) {
            case "docx" -> parseDocx(file, lim);
            case "pptx" -> parsePptx(file, lim);
            case "xlsx" -> parseXlsx(file, lim);
            case "doc" -> docToMarkdown(file, lim);
            default -> throw new IOException("不支持的 Office 类型: " + type);
        };
    }

    // ===== docx（零依赖：zip + StAX） =====

    @SuppressWarnings("unchecked")
    static Parsed parseDocx(Path file, Limits lim) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            long[] used = {0};
            precheckZip(zip, lim);
            ZipEntry main = findEntry(zip, "word/document.xml");
            if (main == null) throw new IOException("缺少 word/document.xml（不是有效的 docx 结构，可能已损坏）: " + file.getFileName());
            StringBuilder body = new StringBuilder();
            int[] chars = {0};
            boolean[] truncated = {false};
            Budget b = new Budget(lim.maxMdChars());
            try {
                readXml(zip, main, lim, used, r -> {
                    int depth = 0, skipUntil = -1, heading = 0;
                    String curElem = "";
                    StringBuilder para = new StringBuilder();
                    boolean inTable = false, inCell = false;
                    List<List<String>> rows = null;
                    List<String> curRow = null;
                    StringBuilder cell = null;
                    while (r.hasNext()) {
                        switch (r.next()) {
                            case XMLStreamReader.START_ELEMENT -> {
                                depth++;
                                String ln = r.getLocalName();
                                curElem = ln;
                                if (skipUntil > 0) break;   // w:del 子树内：只维护深度，内容全跳过
                                switch (ln) {
                                    case "del" -> skipUntil = depth;         // 修订删除内容：绝不当现行事实输出
                                    case "tab" -> para.append(' ');          // PRD：w:tab → 空格
                                    case "br" -> para.append('\n');
                                    case "pStyle" -> {
                                        String v = attr(r, "val");
                                        if (v != null) {
                                            Matcher m = HEADING_STYLE.matcher(v);
                                            if (m.find()) heading = Math.min(6, Integer.parseInt(m.group(1)));
                                            else if (v.equalsIgnoreCase("Title")) heading = 1;
                                        }
                                    }
                                    case "tbl" -> { if (!inTable) { inTable = true; rows = new ArrayList<>(); } }
                                    case "tr" -> curRow = new ArrayList<>();
                                    case "tc" -> { inCell = true; cell = new StringBuilder(); }
                                    default -> { }
                                }
                            }
                            case XMLStreamReader.CHARACTERS -> {
                                // 只收 w:t（含文本框 a:t）；w:instrText（域代码）/w:delText 天然被排除
                                if (skipUntil <= 0 && "t".equals(curElem)) {
                                    String text = r.getText();
                                    chars[0] += text.length();
                                    para.append(text);
                                }
                            }
                            case XMLStreamReader.END_ELEMENT -> {
                                String ln = r.getLocalName();
                                curElem = "";
                                if (skipUntil > 0) {
                                    if (depth == skipUntil) skipUntil = -1;
                                    depth--;
                                    break;
                                }
                                switch (ln) {
                                    case "p" -> {
                                        String text = para.toString().strip();
                                        para.setLength(0);
                                        int hl = heading;
                                        heading = 0;
                                        if (text.isEmpty()) break;
                                        if (inCell) {
                                            if (cell.length() > 0) cell.append(' ');
                                            cell.append(text);
                                        } else {
                                            b.append(body, (hl >= 1 ? "#".repeat(hl) + " " : "") + text + "\n\n", truncated);
                                        }
                                    }
                                    case "tc" -> {
                                        inCell = false;
                                        if (curRow != null) curRow.add(mdCell(cell == null ? "" : cell.toString()));
                                        cell = null;
                                    }
                                    case "tr" -> { if (curRow != null && rows != null) rows.add(curRow); curRow = null; }
                                    case "tbl" -> {
                                        if (inTable) {
                                            renderTable(body, rows, b, truncated);
                                            inTable = false;
                                            rows = null;
                                        }
                                    }
                                    default -> { }
                                }
                                depth--;
                            }
                            default -> { }
                        }
                    }
                });
            } catch (XMLStreamException ex) {
                if (ex.getCause() instanceof IOException io) throw io;   // 安全上限类 IO 错误原样穿透
                throw new IOException("docx XML 解析失败（文件可能已损坏）: " + ex.getMessage(), ex);
            }
            if (truncated[0]) appendTruncNote(body, file);
            return new Parsed(body.toString(), chars[0], truncated[0], -1, -1, -1, -1, false);
        }
    }

    // ===== pptx（零依赖；slide 按数值序，不按文件名字典序） =====

    static Parsed parsePptx(Path file, Limits lim) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            long[] used = {0};
            precheckZip(zip, lim);
            List<int[]> order = new ArrayList<>();   // [slideNo, entryIndex]
            List<ZipEntry> slideEntries = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry ze = en.nextElement();
                Matcher m = SLIDE_PATH.matcher(ze.getName());
                if (m.matches() && !ze.isDirectory()) {
                    order.add(new int[]{Integer.parseInt(m.group(1)), slideEntries.size()});
                    slideEntries.add(ze);
                }
            }
            if (slideEntries.isEmpty())
                throw new IOException("缺少 ppt/slides/slideN.xml（不是有效的 pptx 结构，可能已损坏）: " + file.getFileName());
            order.sort(Comparator.comparingInt(a -> a[0]));   // 数值序：slide2 在 slide10 之前
            StringBuilder body = new StringBuilder();
            int[] chars = {0};
            boolean[] truncated = {false};
            Budget b = new Budget(lim.maxMdChars());
            for (int i = 0; i < order.size(); i++) {
                ZipEntry ze = slideEntries.get(order.get(i)[1]);
                int no = order.get(i)[0];
                if (truncated[0]) {
                    body.append("> （其余 ").append(order.size() - i).append(" 页未纳入：产物预算已用尽）\n");
                    break;
                }
                b.append(body, "## Slide " + no + "\n\n", truncated);
                StringBuilder slideText = new StringBuilder();
                try {
                    readXml(zip, ze, lim, used, r -> {
                        String curElem = "";
                        boolean drawing = false;
                        StringBuilder line = new StringBuilder();
                        while (r.hasNext()) {
                            switch (r.next()) {
                                case XMLStreamReader.START_ELEMENT -> {
                                    curElem = r.getLocalName();
                                    drawing = NS_DRAWING.equals(r.getNamespaceURI());
                                    if (drawing && "p".equals(curElem)) line.setLength(0);
                                }
                                case XMLStreamReader.CHARACTERS -> {
                                    if (drawing && "t".equals(curElem)) {
                                        chars[0] += r.getText().length();
                                        line.append(r.getText());
                                    }
                                }
                                case XMLStreamReader.END_ELEMENT -> {
                                    if (NS_DRAWING.equals(r.getNamespaceURI()) && "p".equals(r.getLocalName())) {   // 事件自身命名空间判断（drawing 会被内层 </a:t> 复位）
                                        String t = line.toString().strip();
                                        if (!t.isEmpty()) slideText.append(t).append("\n\n");
                                        line.setLength(0);
                                    }
                                    curElem = "";
                                }
                                default -> { }
                            }
                        }
                    });
                } catch (IOException ex) {
                    throw ex;   // 安全上限/加密 entry：整个解析必须中止，不许静默跳页假装成功
                } catch (XMLStreamException ex) {
                    if (ex.getCause() instanceof IOException io) throw io;
                    slideText.append("（本页 XML 解析失败，已跳过: ").append(ex.getMessage()).append("）\n\n");
                }
                b.append(body, slideText.length() == 0 ? "（本页无可抽取文本）\n\n" : slideText.toString(), truncated);
            }
            if (truncated[0]) appendTruncNote(body, file);
            return new Parsed(body.toString(), chars[0], truncated[0], -1, -1, -1, slideEntries.size(), false);
        }
    }

    // ===== xlsx（零依赖；sharedStrings + 最多 MAX_SHEETS 个 sheet 转 markdown 表，超预算诚实截断） =====

    static Parsed parseXlsx(Path file, Limits lim) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            long[] used = {0};
            precheckZip(zip, lim);
            ZipEntry wb = findEntry(zip, "xl/workbook.xml");
            if (wb == null) throw new IOException("缺少 xl/workbook.xml（不是有效的 xlsx 结构，可能已损坏）: " + file.getFileName());
            // 1) r:id → worksheet 路径映射
            Map<String, String> rels = new HashMap<>();
            ZipEntry relsEntry = findEntry(zip, "xl/_rels/workbook.xml.rels");
            if (relsEntry != null) {
                try {
                    readXml(zip, relsEntry, lim, used, r -> {
                        while (r.hasNext()) {
                            if (r.next() == XMLStreamReader.START_ELEMENT && "Relationship".equals(r.getLocalName())) {
                                String id = attr(r, "Id"), target = attr(r, "Target");
                                if (id != null && target != null) rels.put(id, resolveSheetTarget(target));
                            }
                        }
                    });
                } catch (XMLStreamException ex) {
                    if (ex.getCause() instanceof IOException io) throw io;
                    throw new IOException("xl/_rels/workbook.xml.rels 解析失败: " + ex.getMessage(), ex);
                }
            }
            // 2) sheet 清单（workbook 顺序）
            List<String[]> sheets = new ArrayList<>();   // [name, zipPath]
            try {
                readXml(zip, wb, lim, used, r -> {
                    boolean inSheets = false;
                    while (r.hasNext()) {
                        int ev = r.next();
                        if (ev != XMLStreamReader.START_ELEMENT && ev != XMLStreamReader.END_ELEMENT) continue;
                        String ln = r.getLocalName();
                        if (ev == XMLStreamReader.START_ELEMENT) {
                            if ("sheets".equals(ln)) inSheets = true;
                            else if (inSheets && "sheet".equals(ln)) {
                                String name = attr(r, "name");
                                String rid = r.getAttributeValue(NS_REL, "id");
                                String path = rid == null ? null : rels.get(rid);
                                if (path == null || findEntry(zip, path) == null)
                                    path = "xl/worksheets/sheet" + (sheets.size() + 1) + ".xml";   // rels 缺失兜底
                                sheets.add(new String[]{name == null || name.isBlank() ? "Sheet" + (sheets.size() + 1) : name, path});
                            }
                        } else if (ev == XMLStreamReader.END_ELEMENT && "sheets".equals(ln)) inSheets = false;
                    }
                });
            } catch (XMLStreamException ex) {
                if (ex.getCause() instanceof IOException io) throw io;
                throw new IOException("xl/workbook.xml 解析失败: " + ex.getMessage(), ex);
            }
            if (sheets.isEmpty()) throw new IOException("xlsx 没有任何工作表（workbook.xml 中无 sheet）: " + file.getFileName());
            // 3) sharedStrings（可选）
            List<String> shared = new ArrayList<>();
            ZipEntry ss = findEntry(zip, "xl/sharedStrings.xml");
            if (ss != null) {
                try {
                    readXml(zip, ss, lim, used, r -> {
                        StringBuilder cur = null;
                        String curElem = "";
                        while (r.hasNext()) {
                            int ev = r.next();
                            String ln = (ev == XMLStreamReader.START_ELEMENT || ev == XMLStreamReader.END_ELEMENT)
                                    ? r.getLocalName() : curElem;
                            if (ev == XMLStreamReader.START_ELEMENT) {
                                curElem = ln;
                                if ("si".equals(ln)) cur = new StringBuilder();
                            } else if (ev == XMLStreamReader.CHARACTERS) {
                                if (cur != null && "t".equals(curElem)) cur.append(r.getText());
                            } else if (ev == XMLStreamReader.END_ELEMENT) {
                                if ("si".equals(ln)) { shared.add(cur == null ? "" : cur.toString()); cur = null; }
                                curElem = "";
                            }
                        }
                    });
                } catch (XMLStreamException ex) {
                    if (ex.getCause() instanceof IOException io) throw io;
                    throw new IOException("xl/sharedStrings.xml 解析失败: " + ex.getMessage(), ex);
                }
            }
            // 4) 逐 sheet 流式转表（预算控制行数；超出后仍继续扫描，行列数保持真实）
            StringBuilder body = new StringBuilder();
            boolean truncated = false;
            int totalRows = 0, maxCols = 0, included = 0;
            Budget b = new Budget(lim.maxMdChars());
            for (int si = 0; si < sheets.size(); si++) {
                String[] sheet = sheets.get(si);
                if (si >= MAX_SHEETS) {
                    truncated = true;
                    StringBuilder names = new StringBuilder();
                    for (int k = si; k < sheets.size(); k++) names.append(k > si ? "、" : "").append(sheets.get(k)[0]);
                    body.append("> 工作簿共 ").append(sheets.size()).append(" 个工作表，仅转换前 ").append(MAX_SHEETS)
                        .append(" 个；未纳入：").append(names).append("\n\n");
                    break;
                }
                ZipEntry ze = findEntry(zip, sheet[1]);
                if (ze == null) {
                    body.append("### Sheet: ").append(sheet[0]).append("\n\n> （缺少 ").append(sheet[1]).append("，无法转换）\n\n");
                    continue;
                }
                int[] sheetRows = {0}, sheetCols = {0};
                boolean[] sheetTruncated = {false};
                List<List<String>> buffered = new ArrayList<>();
                try {
                    readSheetRows(zip, ze, lim, used, shared, b, buffered, sheetRows, sheetCols, sheetTruncated);
                } catch (XMLStreamException ex) {
                    if (ex.getCause() instanceof IOException io) throw io;
                    throw new IOException("工作表 " + sheet[0] + " XML 解析失败（文件可能已损坏）: " + ex.getMessage(), ex);
                }
                totalRows += sheetRows[0];
                maxCols = Math.max(maxCols, sheetCols[0]);
                included++;
                body.append("### Sheet: ").append(sheet[0])
                    .append("（").append(sheetRows[0]).append(" 行 × ").append(sheetCols[0]).append(" 列）\n\n");
                if (!buffered.isEmpty()) {
                    int cols = buffered.stream().mapToInt(List::size).max().orElse(1);
                    for (int ri = 0; ri < buffered.size(); ri++) {
                        body.append('|');
                        for (String c : pad(buffered.get(ri), cols)) body.append(' ').append(c).append(" |");
                        body.append('\n');
                        if (ri == 0) { body.append('|'); body.append(" --- |".repeat(cols)); body.append('\n'); }
                    }
                    body.append('\n');
                }
                if (sheetTruncated[0]) {
                    truncated = true;
                    body.append("> 共 ").append(sheetRows[0]).append(" 行 ").append(sheetCols[0])
                        .append(" 列，已截断（仅保留前 ").append(Math.max(buffered.size() - 1, 0))
                        .append(" 行数据）；完整数据见本地文件 `").append(file).append("`\n\n");
                }
            }
            if (included == 0 || totalRows == 0)
                throw new IOException("xlsx 未找到任何数据行（工作表全为空或结构缺失）: " + file.getFileName());
            if (truncated) appendTruncNote(body, file);
            return new Parsed(body.toString(), body.length(), truncated, totalRows, maxCols, sheets.size(), -1, false);
        }
    }

    /** 流式读一个 worksheet：行缓冲至预算耗尽，之后只计数（真实行列数进截断摘要） */
    private static void readSheetRows(ZipFile zip, ZipEntry ze, Limits lim, long[] used, List<String> shared,
                                      Budget b, List<List<String>> buffered, int[] rows, int[] cols, boolean[] truncated)
            throws IOException, XMLStreamException {
        readXml(zip, ze, lim, used, r -> {
            List<String> row = null;
            String cellType = null, curElem = "";
            StringBuilder cellBuf = null;
            int cellCol = -1, nextCol = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamReader.CHARACTERS) {
                    if (cellBuf != null && ("v".equals(curElem) || "t".equals(curElem))) cellBuf.append(r.getText());
                    continue;
                }
                if (ev != XMLStreamReader.START_ELEMENT && ev != XMLStreamReader.END_ELEMENT) continue;
                String ln = r.getLocalName();
                if (ev == XMLStreamReader.START_ELEMENT) {
                    curElem = ln;
                    switch (ln) {
                        case "row" -> { row = new ArrayList<>(); nextCol = 0; }
                        case "c" -> {
                            cellType = attr(r, "t");
                            cellBuf = new StringBuilder();
                            String ref = attr(r, "r");
                            cellCol = ref != null ? colFromRef(ref) : nextCol;
                            if (cellCol < 0) cellCol = nextCol;
                            nextCol = cellCol + 1;
                        }
                        default -> { }
                    }
                } else if (ev == XMLStreamReader.END_ELEMENT) {
                    switch (ln) {
                        case "c" -> {
                            if (row != null && cellCol >= 0) {
                                String v = cellBuf == null ? "" : cellBuf.toString();
                                if ("s".equals(cellType)) {
                                    try { v = shared.get(Integer.parseInt(v.strip())); }
                                    catch (RuntimeException ex) { v = ""; }   // sharedStrings 索引越界 → 空值（不中断）
                                }
                                while (row.size() < cellCol) row.add("");
                                if (row.size() == cellCol) row.add(mdCell(v));
                                else row.set(cellCol, mdCell(v));
                            }
                            cellType = null; cellBuf = null; cellCol = -1; curElem = "";
                        }
                        case "row" -> {
                            if (row != null) {
                                rows[0]++;
                                cols[0] = Math.max(cols[0], row.size());
                                if (!truncated[0]) {
                                    long est = row.stream().mapToLong(String::length).sum() + row.size() * 3L + 4;
                                    if (b.remaining() < est) truncated[0] = true;   // 预算耗尽：只计数不再缓冲
                                    else { buffered.add(row); b.reserve((int) est); }
                                }
                            }
                            row = null; curElem = "";
                        }
                        default -> curElem = "";
                    }
                }
            }
        });
    }

    private static String resolveSheetTarget(String target) {
        String t = target.replace('\\', '/');
        if (t.startsWith("/")) return t.substring(1);                       // 包内绝对路径
        if (t.startsWith("xl/")) return t;
        return "xl/" + (t.startsWith("./") ? t.substring(2) : t);           // 相对 xl/
    }

    static int colFromRef(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') break;
            col = col * 26 + (c - 'A' + 1);
        }
        return col - 1;
    }

    // ===== doc（Word 97-2003，POI HWPF） =====

    private static final String DOC_NEXT_STEPS = "。若需入库：请在 Word 里「另存为 .docx」或导出 PDF 后重新入库";

    /**
     * .doc → Markdown：段落线性抽取 + 尽力保留表格行列结构。
     * 失败分类抛 {@link DocParseException}（加密/老式 ppt、xls/损坏/非 Word OLE2）；
     * 表格结构抽取异常时降级为线性文本并标记 tablesLost（O2：不许静默当完美结果）。
     * 正文为空/极少的判定（O5）由调用方按 {@link Parsed#extractedChars()} 决定。
     */
    public static Parsed docToMarkdown(Path file) throws IOException {
        return docToMarkdown(file, DEFAULTS);
    }

    static Parsed docToMarkdown(Path file, Limits lim) throws IOException {
        String name = file.getFileName().toString();
        POIFSFileSystem fs;
        try {
            fs = new POIFSFileSystem(file.toFile(), true);
        } catch (IOException | RuntimeException ex) {
            throw new DocParseException("corrupt", "OLE2 复合文档结构损坏，无法读取（" + rootMsg(ex) + "）" + DOC_NEXT_STEPS + ": " + name);
        }
        try {
            DirectoryNode root = fs.getRoot();
            if (root.hasEntry("EncryptedPackage") || root.hasEntry("EncryptionInfo"))
                throw new DocParseException("encrypted", "这是带密码保护的 Office 文件（加密 OLE2 包），无法解析。请移除密码或「另存为 .docx」后重新入库: " + name);
            if (!root.hasEntry("WordDocument")) {
                if (root.hasEntry("PowerPoint Document"))
                    throw new DocParseException("legacy-ppt", "这是老式 PowerPoint .ppt（OLE2），当前不解析。请在 PowerPoint 里「另存为 .pptx」后重新入库: " + name);
                if (root.hasEntry("Workbook"))
                    throw new DocParseException("legacy-xls", "这是老式 Excel .xls（OLE2），当前不解析。请在 Excel 里「另存为 .xlsx」后重新入库: " + name);
                throw new DocParseException("unsupported-ole2", "OLE2 复合文档未找到 Word 正文流（WordDocument），无法解析" + DOC_NEXT_STEPS + ": " + name);
            }
            HWPFDocument doc;
            try {
                doc = new HWPFDocument(fs);
            } catch (Exception ex) {
                String msg = rootMsg(ex);
                boolean encrypted = msg.toLowerCase().contains("encrypt") || msg.contains("加密");
                throw new DocParseException(encrypted ? "encrypted" : "corrupt",
                        (encrypted ? "Word 97-2003 文档已加密，无法解析。请移除密码或「另存为 .docx」后重新入库（"
                                   : "Word 97-2003 文档结构损坏或为更老版本（Word 6/95 不支持），无法解析（")
                        + msg + "）" + DOC_NEXT_STEPS + ": " + name);
            }
            try {
                return extractDoc(doc, file, lim);
            } catch (RuntimeException ex) {
                throw new DocParseException("corrupt", "Word 正文抽取失败（" + rootMsg(ex) + "）" + DOC_NEXT_STEPS + ": " + name);
            }
        } finally {
            try { fs.close(); } catch (IOException ignore) { }
        }
    }

    private static Parsed extractDoc(HWPFDocument doc, Path file, Limits lim) {
        Range range = doc.getRange();
        StringBuilder md = new StringBuilder();
        Budget b = new Budget(lim.maxMdChars());
        boolean[] truncated = {false};
        boolean tablesLost = false;
        int[] chars = {0};
        int i = 0, n = range.numParagraphs();
        while (i < n) {
            Paragraph p = range.getParagraph(i);
            if (p.isInTable()) {
                try {
                    Table t = range.getTable(p);
                    List<List<String>> rows = new ArrayList<>();
                    for (int ri = 0; ri < t.numRows(); ri++) {
                        TableRow tr = t.getRow(ri);
                        List<String> cells = new ArrayList<>();
                        for (int ci = 0; ci < tr.numCells(); ci++) {
                            TableCell tc = tr.getCell(ci);
                            String text = cleanDocText(tc.text()).strip();
                            chars[0] += text.length();
                            cells.add(mdCell(text));
                        }
                        rows.add(cells);
                    }
                    renderTable(md, rows, b, truncated);
                    i += Math.max(t.numParagraphs(), 1);   // 跳过整表段落
                    continue;
                } catch (RuntimeException ex) {
                    tablesLost = true;   // HWPF 表格支持有限：降级线性文本，诚实标注
                }
            }
            String line = cleanDocText(p.text()).strip();
            chars[0] += line.length();
            if (!line.isEmpty()) b.append(md, line + "\n\n", truncated);
            i++;
        }
        if (tablesLost)
            md.append("> ⚠ 该 .doc 含表格但无法还原行列结构，表格已降级为线性文本（meta.tablesLost=true）\n");
        if (truncated[0]) appendTruncNote(md, file);
        return new Parsed(md.toString(), chars[0], truncated[0], -1, -1, -1, -1, tablesLost);
    }

    /** Word97 正文清洗：去域代码指令（\x13..\x14 之间）、单元格标记 \x07、控制符；\t → 空格 */
    static String cleanDocText(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        boolean inInstr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\u0013' -> inInstr = true;              // 域开始
                case '\u0014' -> inInstr = false;             // 域分隔（其后是显示文本）
                case '\u0015', '\u0007' -> { }                // 域结束 / 单元格标记：丢弃
                case '\t' -> { if (!inInstr) out.append(' '); }
                case '\r', '\n' -> { if (!inInstr) out.append('\n'); }
                default -> {
                    if (inInstr || c < 0x20) break;
                    out.append(c);
                }
            }
        }
        return out.toString().replaceAll("\n{3,}", "\n\n");
    }

    private static String rootMsg(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        if (m == null || m.isBlank()) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    // ===== zip / XML 公共工具（安全上限） =====

    /** entry 数与声明解压大小预检（zip bomb 第一道闸；解压流实时计数是第二道） */
    private static void precheckZip(ZipFile zip, Limits lim) throws IOException {
        long total = 0;
        int count = 0;
        for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
            ZipEntry ze = en.nextElement();
            if (++count > MAX_ZIP_ENTRIES)
                throw new IOException("zip entry 数超过 " + MAX_ZIP_ENTRIES + "（疑似 zip 炸弹或异常打包），拒绝解析");
            long s = ze.getSize();
            if (s > 0) total += s;
            if (total > lim.maxTotal())
                throw new IOException("zip 声明解压总量 " + total + " 字节超过安全上限 " + lim.maxTotal() + "（疑似 zip 炸弹），拒绝解析");
        }
    }

    /** 解压流实时计数（entry 声明大小可以撒谎，这里是硬上限；used 为整个解析过程的共享总量计数） */
    private static final class Counted extends FilterInputStream {
        private final long[] used;
        private final long maxEntry, maxTotal;
        private long mine;
        Counted(InputStream in, long[] used, long maxEntry, long maxTotal) {
            super(in);
            this.used = used; this.maxEntry = maxEntry; this.maxTotal = maxTotal;
        }
        private void count(long n) throws IOException {
            if (n <= 0) return;
            mine += n;
            used[0] += n;
            if (mine > maxEntry) throw new IOException("单个 zip entry 解压超过 " + maxEntry + " 字节上限（疑似 zip 炸弹），已中止");
            if (used[0] > maxTotal) throw new IOException("zip 解压总量超过 " + maxTotal + " 字节上限（疑似 zip 炸弹），已中止");
        }
        @Override public int read() throws IOException {
            int c = super.read();
            if (c >= 0) count(1);
            return c;
        }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            count(n);
            return n;
        }
    }

    interface XmlConsumer { void accept(XMLStreamReader r) throws XMLStreamException, IOException; }

    /** 打开 entry 为 StAX 流：先做非 XML 内容嗅探（加密 entry/损坏 → 可读错误），再交给消费函数 */
    private static void readXml(ZipFile zip, ZipEntry entry, Limits lim, long[] used, XmlConsumer consumer)
            throws IOException, XMLStreamException {
        try (InputStream in = new BufferedInputStream(
                new Counted(zip.getInputStream(entry), used, lim.maxEntry(), lim.maxTotal()))) {
            in.mark(64);
            int c;
            do { c = in.read(); } while (c == ' ' || c == '\t' || c == '\r' || c == '\n'
                    || c == 0xEF || c == 0xBB || c == 0xBF);   // 跳过空白与 UTF-8 BOM
            in.reset();
            if (c != '<')
                throw new IOException("zip entry「" + entry.getName() + "」不是有效 XML（文档可能带密码加密或已损坏），拒绝解析");
            consumer.accept(xmlFactory().createXMLStreamReader(in));
        }
    }

    private static XMLInputFactory xmlFactory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);                       // 防 XXE/实体膨胀
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }

    static ZipEntry findEntry(ZipFile zip, String path) {
        ZipEntry e = zip.getEntry(path);
        if (e != null) return e;
        for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {   // 大小写兜底
            ZipEntry ze = en.nextElement();
            if (ze.getName().equalsIgnoreCase(path)) return ze;
        }
        return null;
    }

    private static String attr(XMLStreamReader r, String local) {
        for (int i = 0; i < r.getAttributeCount(); i++)
            if (local.equals(r.getAttributeLocalName(i))) return r.getAttributeValue(i);
        return null;
    }

    // ===== Markdown 输出工具 =====

    /** 产物字符预算：超出即停写并置 truncated，扫描继续（统计数字保持真实） */
    private static final class Budget {
        private final int max;
        private int used;
        Budget(int max) { this.max = max; }
        int remaining() { return max - used; }
        void reserve(int n) { used += n; }
        void append(StringBuilder body, String text, boolean[] truncated) {
            if (used >= max) { truncated[0] = true; return; }
            if (used + text.length() > max) {
                truncated[0] = true;
                body.append(text, 0, Math.max(0, max - used));
                used = max;
                return;
            }
            body.append(text);
            used += text.length();
        }
    }

    private static void renderTable(StringBuilder body, List<List<String>> rows, Budget b, boolean[] truncated) {
        if (rows == null || rows.isEmpty()) return;
        StringBuilder md = new StringBuilder();
        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
        for (int ri = 0; ri < rows.size(); ri++) {
            md.append('|');
            for (String c : pad(rows.get(ri), cols)) md.append(' ').append(c).append(" |");
            md.append('\n');
            if (ri == 0) { md.append('|'); md.append(" --- |".repeat(cols)); md.append('\n'); }
        }
        md.append('\n');
        b.append(body, md.toString(), truncated);
    }

    private static List<String> pad(List<String> row, int cols) {
        if (row.size() == cols) return row;
        List<String> out = new ArrayList<>(row);
        while (out.size() < cols) out.add("");
        return out.size() > cols ? out.subList(0, cols) : out;
    }

    /** 单元格/表格文本规范化：竖线转义、换行制表压成空格、超长截断 */
    static String mdCell(String s) {
        String v = s == null ? "" : s.replace("|", "\\|").replaceAll("[\\r\\n\\t]+", " ").strip();
        if (v.length() > MAX_CELL_CHARS) v = v.substring(0, MAX_CELL_CHARS) + "…";
        return v.isEmpty() ? " " : v;
    }

    private static void appendTruncNote(StringBuilder body, Path file) {
        body.append("\n> ⚠ 产物超出字符预算已截断；完整内容见本地文件 `").append(file).append("`\n");
    }
}
