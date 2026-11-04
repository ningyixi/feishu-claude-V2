package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonElement;
import java.util.Locale;

/** Only execution calls qualify, never listing Postman collections or prose claims. */
final class SupplierProbeEvidence {
    /** Official Postman runCollection exposes test names as text, rather than Newman JSON. */
    private static JsonElement recordedAssertion(String text) {
        var matcher = java.util.regex.Pattern.compile("SUPPLIER_EVIDENCE:(\\{[^\\r\\n]*})").matcher(text);
        if (!matcher.find()) return null;
        try { return com.google.gson.JsonParser.parseString(matcher.group(1)); }
        catch (com.google.gson.JsonParseException ignored) { return null; }
    }

    static boolean hasHttpRequest(JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("method") && object.has("url")) return true;
            for (var entry : object.entrySet()) if (hasHttpRequest(entry.getValue())) return true;
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) if (hasHttpRequest(item)) return true;
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String content = value.getAsString().trim();
            JsonElement recorded = recordedAssertion(content);
            if (recorded != null && hasHttpRequest(recorded)) return true;
            if (content.startsWith("{") || content.startsWith("[")) {
                try { return hasHttpRequest(com.google.gson.JsonParser.parseString(content)); }
                catch (com.google.gson.JsonParseException ignored) { }
            }
        }
        return false;
    }
    static boolean hasHttpResponse(JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) if (hasHttpResponse(item)) return true;
        } else if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            for (String key : new String[]{"code", "statusCode", "status"}) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) {
                    try {
                        int status = object.get(key).getAsInt();
                        if (status >= 100 && status <= 599 && (object.has("body") || object.has("stream"))) return true;
                    } catch (NumberFormatException ignored) { }
                }
            }
            for (var entry : object.entrySet()) if (hasHttpResponse(entry.getValue())) return true;
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String content = value.getAsString().trim();
            JsonElement recorded = recordedAssertion(content);
            if (recorded != null && hasHttpResponse(recorded)) return true;
            if (content.startsWith("{") || content.startsWith("[")) {
                try { return hasHttpResponse(com.google.gson.JsonParser.parseString(content)); }
                catch (com.google.gson.JsonParseException ignored) { }
            }
        }
        return false;
    }
    static boolean isExecutionTool(String name) { return isExecutionTool(name, null); }
    /** 合规执行通道：Postman MCP 工具，或 Bash 调官方 Postman CLI 跑集合（11-04-postman-cli-spec）；裸 curl 不算 */
    static boolean isExecutionTool(String name, JsonElement input) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("_", "");
        if (normalized.contains("postman") && (normalized.contains("sendrequest")
                || normalized.contains("runcollection") || normalized.contains("executecollection"))) return true;
        if (normalized.equals("bash") && input != null && input.isJsonObject() && input.getAsJsonObject().has("command")) {
            String cmd = input.getAsJsonObject().get("command").getAsString().toLowerCase(Locale.ROOT);
            return cmd.matches("(?s).*\\bpostman\\s+collection\\s+run\\b.*") || cmd.matches("(?s).*\\bpostman\\s+run\\b.*");
        }
        return false;
    }
    static String capture(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        String json = value.toString();
        if (json.length() > 500000 || json.equals("[]") || json.equals("{}") || json.equals("\"\"")) return null;
        return json;
    }
}
