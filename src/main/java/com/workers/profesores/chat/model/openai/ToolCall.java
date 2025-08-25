package com.workers.profesores.chat.model.openai;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolCall {
    private String name;
    private JsonNode args;

    public ToolCall() {}
    public ToolCall(String name, JsonNode args) {
        this.name = name;
        this.args = args;
    }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public JsonNode getArgs() { return args; }
    public void setArgs(JsonNode args) { this.args = args; }
}
