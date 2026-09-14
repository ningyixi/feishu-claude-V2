package com.lark.oapi.sample.ws.a2a;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.lark.oapi.sample.ws.model.entity.BotInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * A2A (Agent2Agent) 协议类型 — 移植自 Rust 项目 axocoatl (Apache-2.0) 的 crates/axocoatl-a2a。
 * <p>
 * 线上 JSON 形状与 axocoatl 完全同形（这是 A2A 跨框架互操作的意义所在）：
 * <ul>
 *   <li>字段 snake_case（sender_id / receiver_id / timeout_secs / task_id / input_schema…）</li>
 *   <li>可空字段显式序列化为 null（Gson 默认跳过 null，需要输出时用 JsonObject 手工拼或接受缺省）</li>
 *   <li>{@link TaskStatus} 序列化为裸大写串："Pending"/"Running"/"Completed"/"Failed"/"Cancelled"</li>
 * </ul>
 * 协议面极小 = 5 个 DTO + {@link A2ATaskDispatcher}（axocoatl TaskHandler 的等价 wrapper）。
 * <p>
 * <b>本项目为内部模式</b>：不发布任何 HTTP 端点（无 /.well-known/agent.json、无 POST /tasks），
 * 由 E2E 编排器进程内直调 dispatcher；{@link AgentCard} 仅内部构建，用于描述运行时身份。
 * axocoatl 原实现的欠账（timeout_secs 传而不用、无注册表/上限）在 {@link A2ATaskDispatcher}
 * 与 {@link A2ATaskRegistry} 中补齐。
 */
public final class A2AProtocol {

    private A2AProtocol() {}

    // ===== Agent Card（发现元数据） =====

    /** Agent 名片 — 描述一个运行时里可被 receiver_id 寻址的全部 agent（"一运行时一张卡"聚合模型） */
    public static class AgentCard {
        public String id;
        public String name;
        public String description;
        public String version;
        /** 内部模式不发布 HTTP 端点，恒为空串（axocoatl daemon 填 "/a2a/tasks" 相对路径是其 client 消费不了的坑，不学） */
        public String endpoint = "";
        /** 可寻址的 agent 列表（本项目 = 实例名），调用时用任务的 receiver_id 指名 */
        public List<String> capabilities = new ArrayList<>();
        @SerializedName("input_schema")
        public JsonObject inputSchema;
        @SerializedName("output_schema")
        public JsonObject outputSchema;
        public AuthSpec authentication;
    }

    public static class AuthSpec {
        public String scheme;
        public String endpoint;

        public AuthSpec() {}
        public AuthSpec(String scheme, String endpoint) {
            this.scheme = scheme;
            this.endpoint = endpoint;
        }
    }

    // ===== 任务与结果 =====

    /** 一个 agent 发给另一个 agent 的任务（信封） */
    public static class A2ATask {
        public String id;
        @SerializedName("sender_id")
        public String senderId;
        /** 接收方寻址：实例 id（数字串，优先）或实例名（精确匹配） */
        @SerializedName("receiver_id")
        public String receiverId;
        /** 任务载荷；约定 {"input": "<完整指令文本>"}，宽容提取见 dispatcher */
        public JsonObject input;
        public TaskContext context;
        /** 服务端强制执行（axocoatl 传而不用的欠账，这里补齐）；缺省 = 终端看门狗 + 120s */
        @SerializedName("timeout_secs")
        public Long timeoutSecs;
    }

    /**
     * 任务上下文 — 前三个字段与 axocoatl 同形；后三个是本项目适配终端 Claude wrapper 的扩展。
     * <b>working_dir/model 只接受来自调用方（E2E Member）的实例配置快照值</b>，
     * 不允许任意外部值：ClaudeCodeService.getOrCreate 发现目录/模型变化会 destroy 重建会话
     * （杀死活跃轮次 + 丢整个 CLI 上下文）。
     */
    public static class TaskContext {
        @SerializedName("workflow_id")
        public String workflowId;
        @SerializedName("correlation_id")
        public String correlationId;
        @SerializedName("token_budget")
        public Integer tokenBudget;
        // ---- 本项目扩展 ----
        @SerializedName("session_key")
        public String sessionKey;
        @SerializedName("working_dir")
        public String workingDir;
        public String model;
    }

    /** 任务终态结果；业务失败也是"正常返回"（axocoatl 约定：Failed + error 字符串，而非异常/5xx） */
    public static class A2ATaskResult {
        @SerializedName("task_id")
        public String taskId;
        public TaskStatus status;
        /** 成功时 {"content": "<agent 回复全文>"} */
        public JsonObject output;
        public String error;
    }

    /** 任务状态 — 裸大写串序列化，与 axocoatl 线上格式一致 */
    public enum TaskStatus {
        @SerializedName("Pending") PENDING,
        @SerializedName("Running") RUNNING,
        @SerializedName("Completed") COMPLETED,
        @SerializedName("Failed") FAILED,
        @SerializedName("Cancelled") CANCELLED;

        public String wire() {
            switch (this) {
                case PENDING: return "Pending";
                case RUNNING: return "Running";
                case COMPLETED: return "Completed";
                case FAILED: return "Failed";
                default: return "Cancelled";
            }
        }
    }

    // ===== 构建助手 =====

    /** 内部构建运行时 Agent Card（capabilities = 实例名列表；endpoint 留空 = 内部模式不发布） */
    public static AgentCard cardFor(List<BotInstance> instances) {
        AgentCard card = new AgentCard();
        card.id = "feishu-claude";
        card.name = "Feishu Claude Runtime";
        card.description = "飞书多实例 Claude 终端运行时 — 用任务的 receiver_id（实例 id 或名称）寻址一个 agent。";
        card.version = "1.0.0";
        for (BotInstance i : instances) {
            if (i != null && i.getName() != null && !i.getName().isBlank()) card.capabilities.add(i.getName());
        }
        JsonObject in = new JsonObject();
        in.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject inputProp = new JsonObject();
        inputProp.addProperty("type", "string");
        props.add("input", inputProp);
        in.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("input");
        in.add("required", required);
        card.inputSchema = in;

        JsonObject out = new JsonObject();
        out.addProperty("type", "object");
        JsonObject outProps = new JsonObject();
        JsonObject contentProp = new JsonObject();
        contentProp.addProperty("type", "string");
        outProps.add("content", contentProp);
        out.add("properties", outProps);
        card.outputSchema = out;

        card.authentication = new AuthSpec("none", null);
        return card;
    }

    /** 便捷构造一个文本任务（input={"input":prompt}，correlation_id 缺省 = 任务 id） */
    public static A2ATask textTask(String id, String senderId, String receiverId, String prompt,
                                   String sessionKey, String workingDir, String model) {
        A2ATask t = new A2ATask();
        t.id = id;
        t.senderId = senderId;
        t.receiverId = receiverId;
        JsonObject in = new JsonObject();
        in.addProperty("input", prompt != null ? prompt : "");
        t.input = in;
        TaskContext c = new TaskContext();
        c.correlationId = id;
        c.sessionKey = sessionKey;
        c.workingDir = workingDir;
        c.model = model;
        t.context = c;
        return t;
    }
}
