package com.lark.oapi.sample.ws.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * eval 强制只读安全闸（11-21-supplier-eval-loop MVP-1.6 追加）。
 * <p>
 * 用户原话：「外网请求的，先做查询类的，不要做真实下单的」。这是<b>安全闸，不是偏好</b>：
 * eval 会被交付 loop 反复自动执行，任何写操作端点（下单/取消/支付/退款/确认）都可能对
 * 真实系统产生副作用，必须在起进程之前拦下。
 * <p>
 * 判定对象 = 「这个命令/URL 会不会产生<b>写副作用</b>」，<b>不是</b>「它是不是某个供应商域名」。
 * 允许/禁止两组全部是<b>数据表</b>（本类常量），新增一类目标（如下一棒「自家应用入口」的
 * 具体形态）= 加条目，不改判定结构：
 * <ol>
 *   <li>禁止表 {@link #BLOCKED_PATH_TOKENS}：URL 路径分段命中提交语义 → 一律拒跑，
 *       <b>优先级高于任何允许规则</b>（内网/白名单目标也不例外——测自家服务也不许真实下单）；</li>
 *   <li>允许表 {@link #ALLOW_ENTRIES}：已知只读目标类别（回环/内网应用入口、供应商只读路径），
 *       当前用于显式归类与文案；</li>
 *   <li>两表都未命中的目标：按 {@link #DEFAULT_ALLOW_UNKNOWN_EXTERNAL} 决定（现口径=放行，
 *       因为 http 模板需要能打供应商网关的任意只读端点；将来要收紧成 fail-closed，
 *       翻转该常量或给允许表补条目即可，判定结构不变）。</li>
 * </ol>
 * 设计纪律：纯函数，放在 {@link EvalRunner} 旁边但<b>不进 judge/execute</b>——判定内核语义一行不动。
 * 调用方（导入 / 试跑）命中禁止表即拒绝执行并记 {@link EvalRunner#FAIL}（不是 SKIPPED）——
 * <b>静默跳过的安全闸等于没有闸</b>。确定性纯文本判定：不调用模型、不访问网络。
 */
public final class EvalReadOnlyGuard {

    private EvalReadOnlyGuard() {}

    /**
     * 禁止表（数据；最高优先级，覆盖任何允许规则）：URL 路径分段包含任一片段 = 提交语义，一律禁止。
     * booking/create、booking/cancel 即由 create/cancel 覆盖。
     */
    static final List<String> BLOCKED_PATH_TOKENS = List.of(
            "create", "cancel", "pay", "refund", "confirm", "order");

    /** 允许表条目（数据）：hostRegex（null=不限主机）与 pathContains（null=不限路径）都满足即命中该类 */
    public record AllowEntry(String name, String hostRegex, String pathContains) {}

    /**
     * 允许表（数据；加条目即可扩展，不改判定结构）：
     * ① 回环/内网自家应用入口——eval 端到端打自家服务（经过被测供应商适配器代码）的目标类别，
     *    localhost / 127.x / [::1] / 10.x / 192.168.x / 172.16-31.x（可带端口）；
     * ② 供应商网关已知只读路径：static/*（字典）、avail/*（查价/验价复核）、booking/search
     *    （查询订单状态——search 是查询，不是下单）。
     */
    static final List<AllowEntry> ALLOW_ENTRIES = List.of(
            new AllowEntry("回环/内网自家应用入口",
                    "^(?:localhost|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|\\[::1\\]"
                            + "|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
                            + "|192\\.168\\.\\d{1,3}\\.\\d{1,3}"
                            + "|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})(?::\\d+)?$",
                    null),
            new AllowEntry("供应商静态字典只读 static/*", null, "/static/"),
            new AllowEntry("供应商查价/验价只读 avail/*", null, "/avail/"),
            new AllowEntry("供应商订单状态查询只读 booking/search", null, "/booking/search"));

    /**
     * 未命中禁止表、也未匹配允许表的目标的默认口径：true = 放行（现状——供应商网关的
     * 未知只读端点要能测）。收紧成 fail-closed 时翻转此常量即可，判定结构不变。
     */
    static final boolean DEFAULT_ALLOW_UNKNOWN_EXTERNAL = true;

    /** target 里的 http(s) URL（到空白/引号/反斜杠为止） */
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'\\\\<>]+");

    /** 闸判定结果：blocked=true 时 matched 写清命中的 URL 与片段（进 FAIL reason，可排查） */
    public record GuardResult(boolean blocked, String matched) {
        static GuardResult allow() { return new GuardResult(false, ""); }
    }

    /**
     * 判定一条 eval 的 target 是否会产生写副作用。只对 {@code kind=http/script} 生效——
     * build/test/static 的 target 是本仓库内确定性命令，不含对外部系统的写语义
     *（script 类若带 URL 也会被扫到，判定只看 target 文本里的 URL，不看域名归属）。
     */
    public static GuardResult check(String kind, String target) {
        if (target == null || target.isBlank()) return GuardResult.allow();
        if (!"http".equals(kind) && !"script".equals(kind)) return GuardResult.allow();
        Matcher m = URL.matcher(target);
        while (m.find()) {
            String url = m.group();
            String host = hostOf(url).toLowerCase(Locale.ROOT);
            String path = pathOf(url).toLowerCase(Locale.ROOT);
            // ① 禁止表最优先：命中提交语义 = 拒跑，无论目标是内网应用入口还是白名单主机
            for (String seg : path.split("/")) {
                if (seg.isEmpty()) continue;
                for (String tok : BLOCKED_PATH_TOKENS) {
                    if (seg.contains(tok)) {
                        return new GuardResult(true, url + "（路径段 \"" + seg + "\" 含提交语义 \"" + tok
                                + "\"；禁止表优先于任何允许规则，eval 只允许查询类请求。已知只读目标类别: "
                                + describeAllowed() + "）");
                    }
                }
            }
            // ②③ 允许表归类 + 默认口径：数据驱动，收紧只改 DEFAULT_ALLOW_UNKNOWN_EXTERNAL/允许表条目
            if (!DEFAULT_ALLOW_UNKNOWN_EXTERNAL && matchAllow(host, path) == null) {
                return new GuardResult(true, url + "（未匹配允许表任何只读目标类别，fail-closed 口径下拒绝）");
            }
        }
        return GuardResult.allow();
    }

    /** 允许表归类（返回 null = 未匹配任何条目）：用于文案与将来收紧，不在现口径下改变判定 */
    static AllowEntry matchAllow(String host, String path) {
        for (AllowEntry e : ALLOW_ENTRIES) {
            boolean hostOk = e.hostRegex() == null || host.matches(e.hostRegex());
            boolean pathOk = e.pathContains() == null || path.contains(e.pathContains());
            if (hostOk && pathOk) return e;
        }
        return null;
    }

    /** 允许表类别名清单文案（导入 notes / FAIL reason 用） */
    public static String describeAllowed() {
        StringBuilder sb = new StringBuilder();
        for (AllowEntry e : ALLOW_ENTRIES) {
            if (sb.length() > 0) sb.append("；");
            sb.append(e.name());
        }
        return sb.toString();
    }

    /** URL → host[:port]（去 scheme 与路径） */
    static String hostOf(String url) {
        String s = url.replaceFirst("^https?://", "");
        int slash = s.indexOf('/');
        return slash < 0 ? s : s.substring(0, slash);
    }

    /** URL → 路径部分（去 scheme/host，截断 ?query 与 #fragment；query 串不参与判定） */
    static String pathOf(String url) {
        String s = url.replaceFirst("^https?://", "");
        int slash = s.indexOf('/');
        if (slash < 0) return "";
        s = s.substring(slash);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        return s;
    }
}
