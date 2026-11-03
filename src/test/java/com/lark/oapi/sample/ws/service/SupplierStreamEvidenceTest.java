package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupplierStreamEvidenceTest {
    @Test void actualStreamToolResultFlowsIntoNodeEvidence() throws Exception {
        var node = new SupplierJobNode();
        var service = new SupplierJobService(null, null, null, null, null, null, null, null);
        var progress = SupplierJobService.class.getDeclaredMethod("progressListener", SupplierJobNode.class);
        progress.setAccessible(true);
        TurnListener listener = (TurnListener) progress.invoke(service, node);
        var turnClass = Class.forName(TerminalSession.class.getName() + "$Turn");
        var ctor = turnClass.getDeclaredConstructor(TurnListener.class); ctor.setAccessible(true);
        Object turn = ctor.newInstance(listener);
        var session = new TerminalSession("supplier-proof-test", "", "");
        var current = TerminalSession.class.getDeclaredField("currentTurn"); current.setAccessible(true); current.set(session, turn);
        var assistant = TerminalSession.class.getDeclaredMethod("handleAssistant", JsonObject.class); assistant.setAccessible(true);
        var user = TerminalSession.class.getDeclaredMethod("handleUser", JsonObject.class); user.setAccessible(true);
        try {
            // Only onToolEnd contributes evidence; tool start is insufficient.
            assistant.invoke(session, JsonParser.parseString("""
                {"message":{"content":[{"type":"tool_use","id":"call-1","name":"mcp__postman__sendRequest","input":{"method":"GET","url":"https://supplier.example/api"}}]}}
                """).getAsJsonObject());
            assertTrue(node.getToolEvidence().isBlank());
            user.invoke(session, JsonParser.parseString("""
                {"message":{"content":[{"type":"tool_result","tool_use_id":"call-1","content":[{"type":"text","text":"{\\"response\\":{\\"code\\":200,\\"body\\":\\"real response fixture\\"}}"}]}]}}
                """).getAsJsonObject());
            assertTrue(node.getToolEvidence().contains("supplier.example"));
            assertTrue(node.getToolEvidence().contains("real response fixture"));
        } finally { session.destroy(); }
    }
}
