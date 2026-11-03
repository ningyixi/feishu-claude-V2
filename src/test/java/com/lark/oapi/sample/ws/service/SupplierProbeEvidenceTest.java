package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupplierProbeEvidenceTest {
    @Test void onlyExecutionToolsWithHttpResponsesQualify() {
        assertTrue(SupplierProbeEvidence.isExecutionTool("mcp__postman__runCollection"));
        assertTrue(SupplierProbeEvidence.isExecutionTool("mcp__postman__send_request"));
        assertFalse(SupplierProbeEvidence.isExecutionTool("mcp__postman__getCollection"));
        assertFalse(SupplierProbeEvidence.isExecutionTool("Bash"));
        assertFalse(SupplierProbeEvidence.hasHttpResponse(JsonParser.parseString("{\"success\":true}")));
        assertFalse(SupplierProbeEvidence.hasHttpResponse(JsonParser.parseString("{\"error\":\"timeout\"}")));
        assertTrue(SupplierProbeEvidence.hasHttpResponse(JsonParser.parseString("{\"response\":{\"code\":200,\"body\":\"actual reply\"}}")));
        assertTrue(SupplierProbeEvidence.hasHttpRequest(JsonParser.parseString("{\"method\":\"GET\",\"url\":\"https://supplier.example/test\"}")));
        assertFalse(SupplierProbeEvidence.hasHttpRequest(JsonParser.parseString("{\"collectionId\":\"id\"}")));
        var officialSummary = new com.google.gson.JsonPrimitive("✓ SUPPLIER_EVIDENCE:{\"request\":{\"method\":\"GET\",\"url\":\"https://supplier.example\"},\"response\":{\"code\":200,\"body\":\"recorded reply\"}}\n=== Run completed ===");
        assertTrue(SupplierProbeEvidence.hasHttpRequest(officialSummary));
        assertTrue(SupplierProbeEvidence.hasHttpResponse(officialSummary));
        assertFalse(SupplierProbeEvidence.hasHttpResponse(new com.google.gson.JsonPrimitive("Total requests: 1; Passed: 1")));
        assertNull(SupplierProbeEvidence.capture(JsonParser.parseString("{}")));
    }
}
