package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E2E 群聊编排器 — AgentCoder 三角色闭环的落地实现。
 * <p>
 * 流程（对照 AgentCoder: programmer / test designer / test executor + 迭代反馈环）：
 * <pre>
 * 用户: "端到端测试xxx"
 *   → 👨‍💻 程序员 在群里汇报代码改动（详情写共享目录 .e2e/&lt;task&gt;/changes.md）
 *   → 🧪 测试设计师 只依据需求+git事实 独立设计用例（独立性原则，不给实现放水）
 *   → ⚙️ 执行者 真实执行逐条判定（verdict JSON: pass/fail/blocked + 证据）
 *   → 有失败 → 详情反馈 👨‍💻 修复 → ⚙️ 重测未通过用例（最多 maxRounds 轮）
 *   → 终局：群聊通告 + 飞书推送成员
 * </pre>
 * 每个成员的发言都是其<b>飞书同款常驻会话</b>上的一轮（ClaudeCodeService 终端池复用），
 * 上下文天然延续，且群聊发言经 BrowserTurnListener 实时广播到浏览器。
 * <p>
 * 同目录模式（sharedDir 非空）：大内容走 {@code <sharedDir>/.e2e/<taskId>/} 文件交换，
 * 群聊只留精简摘要；跨目录模式：关键内容全文进群聊消息（互相看不到对方文件）。
 */
@Service
public class E2EOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(E2EOrchestrator.class);
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private final E2ETaskService taskService;
    private final ClaudeCodeService claudeCodeService;
    private final FeishuBotService feishuBotService;

    public E2EOrchestrator(E2ETaskService taskService,
                           ClaudeCodeService claudeCodeService,
                           FeishuBotService feishuBotService) {
        this.taskService = taskService;
        this.claudeCodeService = claudeCodeService;
        this.feishuBotService = feishuBotService;
    }

    // ===== 入口 =====

    /** 启动流水线（幂等：只启动一次） */
    public void start(E2ETaskService.E2ETask task) {
        if (task.pipelineStarted) return;
        task.pipelineStarted = true;
        taskService.persistTask(task);
        Thread.ofVirtual().name("e2e-orch-" + task.id).start(() -> runPipeline(task, false));
    }

    /** 断点续跑（应用重启打断后由前端"继续执行"按钮触发）：有用例则跳过汇报/设计直接进执行阶段 */
    public void resumePipeline(E2ETaskService.E2ETask task) {
        if (!task.pipelineStarted || task.pipelineDone || task.busy) return;
        task.interrupted = false;
        task.cancelled = false;
        taskService.persistTask(task);
        Thread.ofVirtual().name("e2e-orch-resume-" + task.id).start(() -> runPipeline(task, true));
    }

    /**
     * 用户在群里发言。
     * @param to "auto"（驱动流水线/回答提问）或角色 programmer/designer/executor（直接单聊该成员）
     */
    public void postUserMessage(E2ETaskService.E2ETask task, String text, String to) {
        E2ETaskService.TurnRecord rec = taskService.beginTurn(task, text, "user", "user", "用户");
        rec.addressedTo = (to == null || to.isBlank()) ? "auto" : to;
        taskService.persistTurn(task, rec);   // 回填收件人
        JsonObject e = new JsonObject();
        e.addProperty("type", "user_message");
        e.addProperty("turnIndex", task.transcript.indexOf(rec));
        e.addProperty("text", text);
        e.addProperty("to", rec.addressedTo);
        taskService.broadcast(task.id, e);

        if (to == null || to.isBlank() || "auto".equals(to)) {
            // 流水线暂停提问中 → 本条消息就是答案，投递并恢复流水线
            E2ETaskService.PendingQuestion pq = task.pendingQuestion;
            java.util.concurrent.CompletableFuture<String> fut = task.answerFuture;
            if (pq != null && fut != null) {
                fut.complete(text);
                return;
            }
            if (!task.pipelineStarted) {
                // 用户的首条发言可以补充/细化需求（不只是干巴巴的"开始"）
                String t = text.trim();
                boolean isPlainStart = t.equals("开始") || t.equalsIgnoreCase("start") || t.length() <= 6;
                if (!isPlainStart && !task.requirement.contains(t)) {
                    task.requirement = task.requirement + "\n用户补充指示: " + t;
                    taskService.persistTask(task);
                }
                start(task);
            } else if (!task.pipelineDone) {
                taskService.postSystem(task, "ℹ️ 流水线执行中。要给某个成员单独发消息，请用 @程序员 / @测试设计师 / @执行者。");
            } else {
                taskService.postSystem(task, "ℹ️ 本次任务已结束。可以 @成员 继续单聊追问，或新建任务。");
            }
            return;
        }

        E2ETaskService.Member m = task.memberByRole(to);
        if (m == null) {
            taskService.postSystem(task, "⚠️ 群里没有角色为 " + to + " 的成员。");
            return;
        }
        // 直接单聊：成员自己的会话里没有别人的发言，附最近群聊摘要补上下文
        Thread.ofVirtual().name("e2e-direct-" + task.id).start(() -> {
            String prompt = "【E2E 测试群聊 · 用户对你说话】\n" + text
                    + "\n\n== 群聊最近消息（背景补充） ==\n" + taskService.recentGroupDigest(task, 8, 300);
            runMemberTurn(task, m, prompt, text);
        });
    }

    // ===== 流水线主体 =====

    private void runPipeline(E2ETaskService.E2ETask task, boolean resume) {
        E2ETaskService.PipelineState ps = task.pipeline;
        try {
            E2ETaskService.Member programmer = task.memberByRole("programmer");
            E2ETaskService.Member designer = task.memberByRole("designer");
            E2ETaskService.Member executor = task.memberByRole("executor");

            if (!resume) {
                taskService.postSystem(task, String.format(
                        "🎬 任务开始 | 需求「%s」 | 成员: %s | 模式: %s | 自动修复: %s（最多%d轮）",
                        E2ETaskService.truncate(task.requirement, 60),
                        memberRoster(task),
                        task.sharedDir != null ? "共享目录（文件中转，群聊精简）" : "跨目录（群聊全文传递）",
                        task.autoFix ? "开" : "关", task.maxRounds));
            } else {
                taskService.postSystem(task, "▶ 应用重启后读档续跑（历史对话与用例已恢复）");
            }

            // ── 阶段1+2：汇报改动 + 设计用例（续跑且用例已就绪时跳过）──
            boolean skipDesign = resume && !ps.cases.isEmpty();
            if (skipDesign) {
                taskService.postSystem(task, "📋 用例已就绪（" + ps.cases.size() + " 条），跳过汇报与设计阶段，直接进入执行");
            } else {
                // ── 阶段1：程序员汇报改动 ──
                String changesSummary = "";
                if (programmer != null) {
                    setStage(task, "PROGRAMMER_REPORT", "程序员正在汇报代码改动");
                    changesSummary = runMemberTurnWithAsk(task, programmer, buildReportPrompt(task), "汇报代码改动");
                    if (task.cancelled) return;
                } else {
                    taskService.postSystem(task, "⚠️ 未指定程序员实例，跳过改动汇报（设计师将只依据需求和 git 事实设计）");
                }

                // ── 阶段2：测试设计师独立设计用例 ──
                if (designer == null) { fail(task, "未指定测试设计师实例，无法生成用例"); return; }
                setStage(task, "DESIGNING", "测试设计师正在独立设计用例");
                String designOut = runMemberTurnWithAsk(task, designer, buildDesignerPrompt(task, changesSummary), "设计测试用例");
                List<E2ETaskService.TestCase> cases = parseTestCases(designOut);
                if (cases.isEmpty()) {   // 容错重试一次：只要 JSON
                    designOut = runMemberTurn(task, designer,
                            "系统未能从你上一条消息解析出用例 JSON。请把你刚设计的用例原样重新输出，"
                                    + "只输出一个 ```json 代码块（数组，元素含 id/title/steps/expected/priority），不要任何其他文字。",
                            "重发用例 JSON");
                    cases = parseTestCases(designOut);
                }
                if (cases.isEmpty()) { fail(task, "无法解析测试用例 JSON（已重试一次），流水线终止"); return; }
                ps.cases.addAll(cases);
                broadcastCases(task);
                taskService.postSystem(task, "📋 用例已就绪：共 " + cases.size() + " 条，进入执行阶段");
                if (task.cancelled) return;
            }

            // ── 阶段3+：执行 → 修复 循环（AgentCoder 迭代反馈环）──
            int startRound = resume ? ps.rounds.size() + 1 : 1;   // 续跑从下一轮开始，重新给满轮次预算
            for (int round = startRound; round < startRound + task.maxRounds; round++) {
                ps.round = round;
                List<E2ETaskService.TestCase> toRun = new ArrayList<>();
                for (E2ETaskService.TestCase c : ps.cases) {
                    if (round == startRound || !"pass".equals(c.status)) toRun.add(c);
                }
                // 续跑首轮也只跑未通过的（已 pass 的不重复执行）
                if (resume && round == startRound) {
                    toRun.removeIf(c -> "pass".equals(c.status));
                }
                if (toRun.isEmpty()) break;
                if (executor == null) { fail(task, "未指定执行者实例，无法执行测试"); return; }

                setStage(task, "EXECUTING", "第 " + round + " 轮：执行 " + toRun.size() + " 条用例");
                for (E2ETaskService.TestCase c : toRun) {
                    c.status = "running";
                    broadcastCase(task, c);
                }
                String execOut = runMemberTurnWithAsk(task, executor, buildExecutorPrompt(task, toRun, round),
                        "执行第 " + round + " 轮测试");
                List<JsonObject> verdicts = parseVerdicts(execOut);
                if (verdicts.isEmpty()) {
                    execOut = runMemberTurn(task, executor,
                            "系统未能解析你的判定 JSON。请重新输出本轮执行结果，只输出一个 ```json 代码块"
                                    + "（数组，元素含 id/status(pass|fail|blocked)/evidence/failReason），不要任何其他文字。",
                            "重发判定 JSON");
                    verdicts = parseVerdicts(execOut);
                }
                applyVerdicts(task, toRun, verdicts, round);
                E2ETaskService.RoundReport rep = tally(toRun, round);
                ps.rounds.add(rep);
                broadcastRound(task, rep);
                if (task.cancelled) return;

                long notPassed = ps.cases.stream().filter(c -> !"pass".equals(c.status)).count();
                if (notPassed == 0) { finish(task, true); return; }
                if (!task.autoFix || round >= startRound + task.maxRounds - 1 || programmer == null) { finish(task, false); return; }

                setStage(task, "FIXING", "已反馈程序员修复（" + notPassed + " 条未通过）");
                String fixOut = runMemberTurnWithAsk(task, programmer, buildFixPrompt(task, round),
                        "修复第 " + round + "轮发现的问题");
                rep.fixSummary = E2ETaskService.truncate(fixOut, 400);
                taskService.persistTask(task);   // fixSummary 落库
                if (task.cancelled) return;
            }
            finish(task, false);
        } catch (Exception e) {
            log.error("[E2E] 流水线异常: task={}", task.id, e);
            fail(task, "流水线异常: " + e.getMessage());
        }
    }

    /** 成员单轮发言：复用它自己的飞书常驻会话（终端池），发言流入群聊 */
    public String runMemberTurn(E2ETaskService.E2ETask task, E2ETaskService.Member m, String prompt, String displayText) {
        E2ETaskService.TurnRecord rec = taskService.beginTurn(task, displayText, "agent", m.role, m.name);
        BrowserTurnListener listener = new BrowserTurnListener(taskService, task, rec, m.role);
        try {
            String reply = claudeCodeService.chat(m.instanceId, "e2e-" + task.id, prompt, null, m.workingDir, m.model, listener);
            return reply != null ? reply : "";
        } finally {
            taskService.endTurn(task);
        }
    }

    /** 单个阶段内最多允许的提问轮数（防止 ask 死循环） */
    private static final int MAX_ASK_ROUNDS = 3;

    /**
     * 带"主动提问"能力的成员发言 — 流水线各阶段统一走这里：
     * 成员输出 ```ask 块 → 流水线暂停、群里横幅提问 → 玩家用"自动编排"回复 →
     * 答案投递给提问成员（它的会话有上下文，接着干）→ 拿到不含 ask 的输出为止。
     */
    private String runMemberTurnWithAsk(E2ETaskService.E2ETask task, E2ETaskService.Member m, String prompt, String displayText) {
        String out = runMemberTurn(task, m, prompt, displayText);
        int rounds = 0;
        while (rounds < MAX_ASK_ROUNDS && !task.cancelled) {
            JsonObject ask = extractAsk(out);
            if (ask == null) return out;

            String question = ask.has("question") && !ask.get("question").isJsonNull()
                    ? ask.get("question").getAsString() : "需要玩家补充信息才能继续";
            String why = ask.has("why") && !ask.get("why").isJsonNull() ? ask.get("why").getAsString() : "";

            E2ETaskService.PendingQuestion pq = new E2ETaskService.PendingQuestion(m.role, question, why);
            java.util.concurrent.CompletableFuture<String> fut = new java.util.concurrent.CompletableFuture<>();
            task.pendingQuestion = pq;
            task.answerFuture = fut;
            broadcastAsk(task, m, pq);
            taskService.postSystem(task, String.format(
                    "⏸ 流水线暂停 — %s%s「%s」向你提问：%s%s\n👉 在输入框保持「🎯 自动编排」直接回复答案，流水线将自动继续。",
                    m.roleEmoji(), m.roleLabel(), m.name, question, why.isBlank() ? "" : "（" + why + "）"));
            log.info("[E2E] 流水线暂停等玩家回答: task={}, 提问方={}, question={}", task.id, m.role, question);

            String answer;
            try {
                answer = fut.get();   // 虚拟线程上无限等待是廉价的；任务删除时 future 会被 complete
            } catch (Exception e) {
                task.pendingQuestion = null;
                task.answerFuture = null;
                return out;
            }
            task.pendingQuestion = null;
            task.answerFuture = null;
            if (task.cancelled) return out;
            broadcastAskResolved(task);

            out = runMemberTurn(task, m,
                    "【E2E 测试群聊 · 玩家回答】\n你刚才提问：「" + question + "」\n玩家的回答：\n" + answer
                            + "\n\n请根据回答继续完成之前的任务，并按原要求输出内容（包括原定的 JSON 契约）。仍然缺关键信息才可以再次提问。",
                    "收到玩家回答，继续执行");
            rounds++;
        }
        if (extractAsk(out) != null) {
            taskService.postSystem(task, "⚠️ 提问轮数已达上限（" + MAX_ASK_ROUNDS + "），流水线继续推进，受影响的用例可能被判为受阻。");
        }
        return out;
    }

    /** 提取 ```ask 围栏块（取最后一个） */
    static JsonObject extractAsk(String text) {
        if (text == null || text.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("```ask\\s*([\\s\\S]*?)```").matcher(text);
        JsonObject last = null;
        while (m.find()) {
            try {
                JsonElement el = JsonParser.parseString(m.group(1).trim());
                if (el.isJsonObject()) last = el.getAsJsonObject();
            } catch (Exception ignored) {}
        }
        return last;
    }

    private void broadcastAsk(E2ETaskService.E2ETask task, E2ETaskService.Member m, E2ETaskService.PendingQuestion pq) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "ask");
        e.addProperty("role", m.role);
        e.addProperty("roleLabel", m.roleLabel());
        e.addProperty("roleEmoji", m.roleEmoji());
        e.addProperty("memberName", m.name);
        e.addProperty("question", pq.question);
        e.addProperty("why", pq.why);
        e.addProperty("sinceMs", pq.sinceMs);
        taskService.broadcast(task.id, e);
    }

    private void broadcastAskResolved(E2ETaskService.E2ETask task) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "ask_resolved");
        taskService.broadcast(task.id, e);
    }

    // ===== Prompt 模板（角色设定照搬 AgentCoder 三 agent 分工） =====

    /** 提问契约 — 拼进所有成员的 prompt：缺关键信息时暂停问玩家，不许猜 */
    private static final String ASK_CONTRACT =
            "\n\n【提问规则】如果继续任务需要玩家提供关键信息（接口凭证/token/测试账号/环境地址/业务决策等），"
            + "**不要猜测、不要编造、不要跳过**，在你发言的最后输出一个提问块然后停止：\n"
            + "```ask\n{\"question\":\"要问玩家的问题（一句话，具体）\",\"why\":\"缺了它会影响什么\"}\n```\n"
            + "系统会暂停流水线把问题转给玩家，拿到回答后会让你继续。一次只问最关键的一个问题。";

    private String sharedNote(E2ETaskService.E2ETask task) {
        if (task.sharedDir != null) {
            return "所有成员共享工作目录: " + task.sharedDir + "\n"
                    + "约定：大篇幅内容通过文件交换——任务文件目录为 " + task.exchangeDirName()
                    + "（相对工作目录，不存在就创建），群聊里只发精简摘要。\n";
        }
        return "注意：各成员工作目录不同，你无法直接读到其他成员的文件系统，重要内容必须完整写在你的群聊发言里。\n";
    }

    private String memberRoster(E2ETaskService.E2ETask task) {
        StringBuilder sb = new StringBuilder();
        for (E2ETaskService.Member m : task.members) {
            if (sb.length() > 0) sb.append("、");
            sb.append(m.roleEmoji()).append(m.roleLabel()).append("「").append(m.name).append("」");
        }
        return sb.toString();
    }

    private String buildReportPrompt(E2ETaskService.E2ETask task) {
        E2ETaskService.Member designer = task.memberByRole("designer");
        E2ETaskService.Member executor = task.memberByRole("executor");
        return "【E2E 测试群聊 · 启动】\n"
                + "被测需求: " + task.requirement + "\n"
                + "你是本群的 👨‍💻 程序员（代码实现方）。其他成员："
                + (designer != null ? "🧪 测试设计师「" + designer.name + "」、" : "")
                + (executor != null ? "⚙️ 测试执行者「" + executor.name + "」" : "")
                + "。设计师将独立设计用例，执行者会真实运行它们来验收你的代码。\n"
                + sharedNote(task)
                + "\n请向群里汇报你针对上述需求最近完成的代码改动：\n"
                + "1. 改动摘要（做了什么、为什么这样做）\n"
                + "2. 关键文件清单\n"
                + "3. 如何运行/验证（启动命令、入口、端口等）\n"
                + (task.sharedDir != null
                    ? "4. 同时把详细改动说明写入 " + task.exchangeDirName() + "/changes.md，供成员直接查阅\n" : "")
                + "群聊汇报请精炼，500 字以内。如果你没有做过相关改动，如实说明现状即可。"
                + ASK_CONTRACT;
    }

    private String buildDesignerPrompt(E2ETaskService.E2ETask task, String changesSummary) {
        E2ETaskService.Member designer = task.memberByRole("designer");
        String dir = designer != null ? designer.workingDir : task.members.get(0).workingDir;
        return "【E2E 测试群聊 · 用例设计】\n"
                + "被测需求: " + task.requirement + "\n"
                + "你是本群的 🧪 测试设计师。**独立性原则**：只根据【需求描述】和下面的【git 变更事实】设计用例，"
                + "不迎合程序员的实现方式和自述，不给代码放水——你的用例将由执行者真实运行。\n\n"
                + "== 程序员的群聊汇报（仅供参考，不可尽信） ==\n"
                + E2ETaskService.truncate(changesSummary, 4000) + "\n\n"
                + "== 被测目录的 git 变更事实 ==\n" + taskService.gitSection(dir) + "\n"
                + sharedNote(task)
                + (task.sharedDir != null ? "程序员的详细改动说明在 " + task.exchangeDirName() + "/changes.md（如存在），也可直接查看共享目录代码。\n" : "")
                + "\n设计要求：\n"
                + "- 用例 3~10 条，必须覆盖：正常路径 ≥1、边界条件 ≥1、异常/错误处理 ≥1\n"
                + "- steps 必须是可真实执行的操作（命令 / HTTP 请求 / 文件检查 / 服务启动），expected 必须可客观判定\n"
                + (task.sharedDir != null ? "- 完整用例集写入 " + task.exchangeDirName() + "/testcases.md\n" : "")
                + "- 群聊汇报：用例总数 + 每条一行标题，300 字以内\n"
                + "- 发言最后必须输出一个 ```json 代码块，格式：\n"
                + "[{\"id\":\"TC-1\",\"title\":\"...\",\"steps\":\"...\",\"expected\":\"...\",\"priority\":\"high|medium|low\"}]"
                + ASK_CONTRACT;
    }

    private String buildExecutorPrompt(E2ETaskService.E2ETask task, List<E2ETaskService.TestCase> toRun, int round) {
        JsonArray arr = new JsonArray();
        for (E2ETaskService.TestCase c : toRun) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id);
            o.addProperty("title", c.title);
            o.addProperty("steps", c.steps);
            o.addProperty("expected", c.expected);
            arr.add(o);
        }
        boolean retest = round > 1;
        return "【E2E 测试群聊 · 第 " + round + " 轮执行】\n"
                + "你是本群的 ⚙️ 测试执行者。**诚实原则**：一切以真实执行结果为准，不粉饰、不臆断；"
                + "无法执行的用例标 blocked 并说明原因。\n"
                + (retest ? "本轮为复测，只执行上次未通过的用例（程序员已修复）：\n" : "请逐条真实执行以下用例（跑命令/起服务/发请求/查文件）：\n")
                + arr + "\n"
                + sharedNote(task)
                + (task.sharedDir != null ? "完整用例见 " + task.exchangeDirName() + "/testcases.md。\n" : "")
                + "\n执行完成后：\n"
                + (task.sharedDir != null ? "- 完整报告写入 " + task.exchangeDirName() + "/report-r" + round + ".md（每条用例的命令、输出摘录、结论）\n" : "")
                + "- 群聊汇报：通过/失败/受阻数量 + 每条失败的原因与关键证据，400 字以内\n"
                + "- 发言最后必须输出一个 ```json 代码块，格式：\n"
                + "[{\"id\":\"TC-1\",\"status\":\"pass|fail|blocked\",\"evidence\":\"关键证据（输出摘录/响应码）\",\"failReason\":\"若失败：具体现象与定位线索\"}]"
                + ASK_CONTRACT;
    }

    private String buildFixPrompt(E2ETaskService.E2ETask task, int round) {
        StringBuilder failed = new StringBuilder();
        for (E2ETaskService.TestCase c : task.pipeline.cases) {
            if ("pass".equals(c.status)) continue;
            failed.append("- [").append(c.id).append("] ").append(c.title)
                    .append(" → ").append("fail".equals(c.status) ? "❌ 失败" : "🚫 受阻").append("\n")
                    .append("  现象: ").append(E2ETaskService.truncate(c.failReason, 300)).append("\n")
                    .append("  证据: ").append(E2ETaskService.truncate(c.evidence, 300)).append("\n");
        }
        return "【E2E 测试群聊 · 第 " + round + " 轮问题修复】\n"
                + "你是本群的 👨‍💻 程序员。执行者对你的「" + E2ETaskService.truncate(task.requirement, 50) + "」实测发现以下问题：\n"
                + failed + "\n"
                + (task.sharedDir != null ? "完整报告: " + task.exchangeDirName() + "/report-r" + round + ".md\n" : "")
                + "请逐一修复，修复后必须自验（运行相关命令/测试确认生效），然后在群里汇报：改了什么、怎么验证的，500 字以内。"
                + ASK_CONTRACT;
    }

    // ===== 判定解析（容错：fence 优先，其次括号扫描） =====

    List<E2ETaskService.TestCase> parseTestCases(String text) {
        List<E2ETaskService.TestCase> out = new ArrayList<>();
        JsonArray arr = extractJsonArray(text);
        if (arr == null) return out;
        int n = 0;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            E2ETaskService.TestCase c = new E2ETaskService.TestCase();
            c.id = optString(o, "id", "TC-" + (++n));
            c.title = optString(o, "title", "(未命名用例)");
            c.steps = optString(o, "steps", "");
            c.expected = optString(o, "expected", "");
            c.priority = optString(o, "priority", "medium");
            out.add(c);
            n++;
        }
        return out;
    }

    List<JsonObject> parseVerdicts(String text) {
        List<JsonObject> out = new ArrayList<>();
        JsonArray arr = extractJsonArray(text);
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String status = optString(o, "status", "").toLowerCase();
            if (status.equals("pass") || status.equals("fail") || status.equals("failed")
                    || status.equals("blocked") || status.equals("skip") || status.equals("skipped")) {
                out.add(o);
            }
        }
        return out;
    }

    static JsonArray extractJsonArray(String text) {
        if (text == null || text.isBlank()) return null;
        // 1) ``` 围栏块（取最后一个能解析成数组的，通常最终答案在末尾）
        Matcher m = FENCE.matcher(text);
        JsonArray fenced = null;
        while (m.find()) {
            JsonArray a = tryParseArray(m.group(1).trim());
            if (a != null) fenced = a;
        }
        if (fenced != null) return fenced;
        // 2) 裸文本括号扫描（平衡匹配，跳过字符串内的括号）
        int start = text.indexOf('[');
        int attempts = 0;
        while (start >= 0 && attempts++ < 10) {
            int end = matchBracket(text, start);
            if (end > start) {
                JsonArray a = tryParseArray(text.substring(start, end + 1));
                if (a != null && a.size() > 0) return a;
            }
            start = text.indexOf('[', start + 1);
        }
        return null;
    }

    private static JsonArray tryParseArray(String s) {
        try {
            JsonElement el = JsonParser.parseString(s);
            if (el.isJsonArray()) return el.getAsJsonArray();
            if (el.isJsonObject()) {   // {"cases":[...]} 包装形态
                for (var entry : el.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonArray()) return entry.getValue().getAsJsonArray();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 start 位置的 '[' 找到平衡的 ']'，返回下标；找不到返回 -1 */
    private static int matchBracket(String s, int start) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static String optString(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    // ===== 判定应用与轮次统计 =====

    private void applyVerdicts(E2ETaskService.E2ETask task, List<E2ETaskService.TestCase> toRun,
                               List<JsonObject> verdicts, int round) {
        for (E2ETaskService.TestCase c : toRun) {
            JsonObject v = verdicts.stream()
                    .filter(o -> optString(o, "id", "").trim().equalsIgnoreCase(c.id.trim()))
                    .findFirst().orElse(null);
            if (v == null) {
                c.status = "blocked";
                c.failReason = "执行者未返回该用例的判定";
            } else {
                String st = optString(v, "status", "blocked").toLowerCase();
                c.status = switch (st) {
                    case "pass" -> "pass";
                    case "fail", "failed" -> "fail";
                    default -> "blocked";   // blocked/skip
                };
                c.evidence = E2ETaskService.truncate(optString(v, "evidence", ""), 500);
                c.failReason = E2ETaskService.truncate(optString(v, "failReason", ""), 500);
            }
            c.lastRound = round;
            broadcastCase(task, c);
        }
        taskService.persistTask(task);   // 本轮判定结果整体落库
    }

    private E2ETaskService.RoundReport tally(List<E2ETaskService.TestCase> toRun, int round) {
        E2ETaskService.RoundReport rep = new E2ETaskService.RoundReport();
        rep.round = round;
        rep.total = toRun.size();
        for (E2ETaskService.TestCase c : toRun) {
            switch (c.status) {
                case "pass" -> rep.passed++;
                case "fail" -> rep.failed++;
                default -> rep.blocked++;
            }
            // 逐用例判定痕迹快照（截断防止广播/快照过大）
            rep.results.add(new E2ETaskService.CaseResult(
                    c.id, c.title, c.status,
                    E2ETaskService.truncate(c.evidence, 200),
                    E2ETaskService.truncate(c.failReason, 200)));
        }
        return rep;
    }

    // ===== 阶段/终局 =====

    private void setStage(E2ETaskService.E2ETask task, String stage, String detail) {
        task.pipeline.stage = stage;
        task.pipeline.stageDetail = detail;
        taskService.persistTask(task);
        JsonObject e = new JsonObject();
        e.addProperty("type", "stage");
        e.addProperty("stage", stage);
        e.addProperty("detail", detail);
        e.addProperty("round", task.pipeline.round);
        taskService.broadcast(task.id, e);
    }

    private void finish(E2ETaskService.E2ETask task, boolean allPass) {
        task.pipelineDone = true;
        task.interrupted = false;
        E2ETaskService.PipelineState ps = task.pipeline;
        ps.stage = "DONE";
        long passed = ps.cases.stream().filter(c -> "pass".equals(c.status)).count();
        String summary = String.format("%s 端到端测试%s | 需求「%s」 | 第 %d 轮 | 用例 %d/%d 通过%s",
                allPass ? "✅" : "⚠️", allPass ? "全部通过" : "结束（仍有未通过项）",
                E2ETaskService.truncate(task.requirement, 40),
                ps.round, passed, ps.cases.size(),
                allPass ? "" : "，未通过: " + ps.cases.stream().filter(c -> !"pass".equals(c.status))
                        .map(c -> c.id).reduce((a, b) -> a + "," + b).orElse(""));
        ps.stageDetail = summary;
        taskService.postSystem(task, summary);
        broadcastStage(task);
        taskService.persistTask(task);

        // 飞书推送终报（每个成员实例的授权用户，去重）
        List<String> notified = new ArrayList<>();
        for (E2ETaskService.Member m : task.members) {
            if (m.authorizedOpenId.isBlank() || notified.contains(m.instanceId + ":" + m.authorizedOpenId)) continue;
            notified.add(m.instanceId + ":" + m.authorizedOpenId);
            try {
                feishuBotService.notifyUser(m.instanceId, m.authorizedOpenId,
                        "🧪 E2E 测试群聊结果\n\n" + summary + "\n\n详情到管理面板「E2E 测试」页查看（任务 " + task.id + "）");
            } catch (Exception e) {
                log.warn("[E2E] 飞书终报推送失败: {}", e.getMessage());
            }
        }
    }

    private void fail(E2ETaskService.E2ETask task, String reason) {
        task.pipelineDone = true;
        task.interrupted = false;
        task.pipeline.stage = "FAILED";
        task.pipeline.failReason = reason;
        task.pipeline.stageDetail = reason;
        taskService.postSystem(task, "❌ 流水线终止：" + reason);
        broadcastStage(task);
        taskService.persistTask(task);
    }

    // ===== 广播 =====

    private void broadcastStage(E2ETaskService.E2ETask task) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "stage");
        e.addProperty("stage", task.pipeline.stage);
        e.addProperty("detail", task.pipeline.stageDetail);
        e.addProperty("round", task.pipeline.round);
        taskService.broadcast(task.id, e);
    }

    private void broadcastCases(E2ETaskService.E2ETask task) {
        taskService.persistTask(task);
        JsonObject e = new JsonObject();
        e.addProperty("type", "cases_defined");
        JsonArray arr = new JsonArray();
        for (E2ETaskService.TestCase c : task.pipeline.cases) arr.add(caseJson(c));
        e.add("cases", arr);
        taskService.broadcast(task.id, e);
    }

    private void broadcastCase(E2ETaskService.E2ETask task, E2ETaskService.TestCase c) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "case_update");
        e.add("case", caseJson(c));
        taskService.broadcast(task.id, e);
    }

    private void broadcastRound(E2ETaskService.E2ETask task, E2ETaskService.RoundReport rep) {
        taskService.persistTask(task);
        JsonObject e = new JsonObject();
        e.addProperty("type", "round_report");
        e.addProperty("round", rep.round);
        e.addProperty("passed", rep.passed);
        e.addProperty("failed", rep.failed);
        e.addProperty("blocked", rep.blocked);
        e.addProperty("total", rep.total);
        e.addProperty("atMs", rep.atMs);
        JsonArray results = new JsonArray();
        for (E2ETaskService.CaseResult cr : rep.results) {
            JsonObject o = new JsonObject();
            o.addProperty("id", cr.id);
            o.addProperty("title", cr.title);
            o.addProperty("status", cr.status);
            o.addProperty("evidence", cr.evidence);
            o.addProperty("failReason", cr.failReason);
            results.add(o);
        }
        e.add("results", results);
        taskService.broadcast(task.id, e);
        taskService.postSystem(task, String.format("📊 第 %d 轮结果：%d 通过 / %d 失败 / %d 受阻（共 %d 条）",
                rep.round, rep.passed, rep.failed, rep.blocked, rep.total));
    }

    public static JsonObject caseJson(E2ETaskService.TestCase c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.id);
        o.addProperty("title", c.title);
        o.addProperty("steps", c.steps);
        o.addProperty("expected", c.expected);
        o.addProperty("priority", c.priority);
        o.addProperty("status", c.status);
        o.addProperty("evidence", c.evidence);
        o.addProperty("failReason", c.failReason);
        o.addProperty("lastRound", c.lastRound);
        return o;
    }
}
