# 知识库支持 Office 文档解析（docx/pptx/xlsx 零依赖；.doc 给可执行引导）

## 用户诉求（2026-11-23 原话）
「临时需求里面能不能解析 doc 文件，如果不行的话帮我新增这个功能吧，因为供应商给了一个 doc 文档给我。」

## 现状事实（已读码核实）
- `AdHocService.detectType`（`:203-213`，与 `QuickProbeService.java:237-245` 逐字一致）只认 `http(s)`/`.pdf`/`.html`/`.htm`/`.md`/`.markdown`/`.txt`，其余**抛「无法识别的文档来源」**。
- `SupplierKbService` 类型分派在 `:85-160`，只有 `url / pdf / html / md / chat / note`；**`:173` 的 reparse 白名单** `if (!List.of("url","pdf","html","md").contains(e.getType()))` —— 新类型不同步这里就会"能入库、不能重解析"。
- `pom.xml` **没有 POI / Tika / docx4j**。
- 用户 `D:/supplier-docs` 里的真实文件（按 magic bytes 判定，不是看扩展名）：

```
ASATours Integration Document updated 13 Oct 26.doc  → OLE2 真·Word97-2003（604 KB）
Dida __ Omni Technical Kick-Off (1).pptx             → ZIP=OOXML（11 MB，很可能是截图为主）
Hotel list Nov18.xlsx                                → ZIP=OOXML（2.2 MB）
动销酒店36261家.xlsx                                  → ZIP=OOXML（4 MB，36261 行）
```

## 范围
- **O1 新增 `docx` / `pptx` / `xlsx`，零新依赖**（`java.util.zip` + XML）：
  - `docx`：读 `word/document.xml`，段落 + 表格（表格转 markdown 表）；跳过 `w:instrText`（域代码）与 `w:del`（修订删除内容）；`w:tab`→空格；`pStyle Heading*` → markdown 标题层级。
  - `pptx`：`ppt/slides/slideN.xml` 按 N 数值序输出 `## Slide N` + 文本框内容（**别按文件名字典序**，`slide10` 会排到 `slide2` 前）。
  - `xlsx`：`sharedStrings.xml` + **最多 5 个 sheet** 转 markdown 表；超出走 O4 截断。
  - 安全：解析前校验 entry 声明大小与**总解压字节上限**（zip bomb / 加密 entry / 结构缺失都要给出可读错误，不许 OOM、不许无限循环）。
- **O2 `.doc` 也要真解析（方案由主会话定，2026-11-23 更新，不再征询用户）**：引入 **Apache POI `poi-scratchpad`（HWPF / WordExtractor）** 解析 Word 97-2003。要求：
  - 靠 magic bytes `D0 CF 11 E0` 判 OLE2，**不按扩展名信任**（有人把 docx 改名 .doc，也反过来）。`.doc` 扩展名 + OOXML(zip) 头 → 走 docx 路径并在 meta 里记 `mislabeledExtension=true`；反之亦然。
  - 正文按段落抽取，表格尽量保留行列结构（HWPF 对表格支持有限，**抽不出表格时降级为线性文本 + meta 标注 `tablesLost=true`**，不许静默当成完美）。
  - 加依赖前必须先验证**本机能解析到该 artifact**（`mvn -q dependency:resolve` 或编译）；解析不到就如实回报具体错误并停在"已做完 docx/pptx/xlsx"的状态，**不许**把 `.doc` 静默读成乱码充数。
  - fat jar 增量与新增传递依赖（poi core、commons-io、commons-codec、SparseBitSet 等）要在回报里列出。
- **O2b `.doc` 失败态的可执行引导保留**：HWPF 抛异常/加密文档/抽不到正文时，错误信息必须说清是**哪一类失败**（加密 / 结构损坏 / 空正文）并给出下一步（另存为 .docx、或导出 PDF 再入库），**且不得产生 KB 条目**。

- **O3 类型判定三处同步**：`AdHocService.detectType`、`QuickProbeService.detectType`、`SupplierKbService`（`add` 分派 + `:173` reparse 白名单 + `syncFromJob` 类型归属）。
  历史教训：11-13 那次加 `.html` 时 `syncFromJob` 把它误归成 `pdf`，专门补了修复与回归 —— 本轮必须一次做对，测试用同一参数表跑遍**三个入口**。
- **O4 体积纪律（防"文档"吃光上下文）**：36261 行的 xlsx、11MB 的 pptx 不该整份喂进 prompt。解析产物落 md，但受 `SupplierKbService.contextText` 的 **120,000 字符共享预算**约束；表格类超限时保留前 N 行 + 写入「共 X 行 Y 列，已截断，完整数据见本地文件 `<路径>`」摘要，并在条目 meta 记 `truncated=true` 供页面显示。
- **O5 图片型文档要诚实**：pptx/docx 抽出的正文字数低于阈值（如 < 200 字）时，条目标注「抽取内容极少，该文件可能以图片/截图为主，建议改用 URL 或 PDF 来源」，**不许**静默 `DONE` 假装成功（那条 11MB 的 Kick-Off pptx 大概率就是这种情况）。

## 验收
- AC1 上述 3 个 OOXML 真文件能入库并产出 md：pptx 有 `## Slide N` 分段且序号数值有序、xlsx 有表格、docx 有段落与标题层级。
- **AC2（已作废，见 O2）**：用户 ASATours 那份真 `.doc`（OLE2 604KB）**必须被解析出正文并入库**，产出 md 字数与段落样例需可核对；抽取失败时按 O2b 给分类错误且**不入库**，两种结果都不允许出现"乱码入库"。
- **AC2b**：扩展名与真实格式不符（docx 改名 .doc）时按**真实字节**走对应解析器，并在 meta 记 `mislabeledExtension`。
- AC3 大 xlsx：产物被截断 + `truncated=true` + 摘要含真实行列数；单条不能把 120k 预算吃光。
- AC4 zip bomb / 缺结构 / 加密 entry：可读错误，不 OOM、不死循环（有超时或上限断言）。
- AC5 三个入口（临时需求 / 接口建档 / 工作台补充材料）对同一批扩展名给出**一致**的类型判定（同一参数化测试跑三遍）。
- AC6 `reparse` 对 `docx/pptx/xlsx` 可用（`:173` 白名单已同步）。
- AC7 抽取内容极少的 pptx 走 O5 的诚实标注分支。
- AC8 全量 `mvn test` 红数不超过基线 **`359 跑 / 3 失败 / 1 错误 / 1 跳过`**（4 条为既有 Windows 伪脚本与 temp 环境红）；若改前端，`npm run build` 通过但**不要**写 `src/main/resources/static/`（发布归主会话）。

## 明确不做
不引入 POI / Tika / docx4j / LibreOffice 转换（等用户明确授权）；不做 OCR；不做 .doc 静默降级；不改 Wiki 编译管线；不动 eval 模块与工作流（那是 `11-21-supplier-eval-loop` 的 MVP-2）。

## 依赖与排序
独立一棒，不依赖其它未完成工作。完成后主会话统一 `npm run build` → `mvn package` → `scripts/run-backend-detached.ps1` 重启发布。
