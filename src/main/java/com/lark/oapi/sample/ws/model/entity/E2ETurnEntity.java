package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

/**
 * E2E 群聊消息记录（持久化）— 用户发言/成员发言/系统横幅统一建模。
 * agent 发言在轮次完成时回填 thinking/answer/tools/result（流式期间不落库）。
 */
@Entity
@Table(name = "e2e_turn")
public class E2ETurnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", length = 16)
    private String taskId;

    @Column(name = "turn_index")
    private int turnIndex;

    /** user | agent | system */
    private String kind = "agent";

    /** programmer/designer/executor/user/system */
    private String author = "user";

    @Column(name = "author_name")
    private String authorName = "";

    /** user 消息的收件人: auto|programmer|designer|executor */
    @Column(name = "addressed_to")
    private String addressedTo = "";

    @Lob
    @Column(name = "user_text")
    private String userText = "";

    @Lob
    private String thinking = "";

    @Lob
    private String answer = "";

    @Lob
    @Column(name = "tools_json")
    private String toolsJson = "[]";

    @Lob
    @Column(name = "result_json")
    private String resultJson;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    private boolean aborted = false;

    @Column(name = "start_ms")
    private long startMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public int getTurnIndex() { return turnIndex; }
    public void setTurnIndex(int turnIndex) { this.turnIndex = turnIndex; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getAddressedTo() { return addressedTo; }
    public void setAddressedTo(String addressedTo) { this.addressedTo = addressedTo; }
    public String getUserText() { return userText; }
    public void setUserText(String userText) { this.userText = userText; }
    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getToolsJson() { return toolsJson; }
    public void setToolsJson(String toolsJson) { this.toolsJson = toolsJson; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isAborted() { return aborted; }
    public void setAborted(boolean aborted) { this.aborted = aborted; }
    public long getStartMs() { return startMs; }
    public void setStartMs(long startMs) { this.startMs = startMs; }
}
