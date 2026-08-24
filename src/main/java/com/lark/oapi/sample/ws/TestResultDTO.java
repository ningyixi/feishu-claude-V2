package com.lark.oapi.sample.ws;

public record TestResultDTO(boolean success, String message, String detail) {
    public static TestResultDTO ok(String message) {
        return new TestResultDTO(true, message, null);
    }
    public static TestResultDTO ok(String message, String detail) {
        return new TestResultDTO(true, message, detail);
    }
    public static TestResultDTO fail(String message) {
        return new TestResultDTO(false, message, null);
    }
    public static TestResultDTO fail(String message, String detail) {
        return new TestResultDTO(false, message, detail);
    }
}
