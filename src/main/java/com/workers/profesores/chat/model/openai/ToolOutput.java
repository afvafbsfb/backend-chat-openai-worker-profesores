package com.workers.profesores.chat.model.openai;

public class ToolOutput {
    private String callName;
    private Object output;

    public ToolOutput() {}
    public ToolOutput(String callName, Object output) {
        this.callName = callName;
        this.output = output;
    }
    public static ToolOutput of(ToolCall call, Object output) {
        return new ToolOutput(call.getName(), output);
    }
    public String getCallName() { return callName; }
    public void setCallName(String callName) { this.callName = callName; }
    public Object getOutput() { return output; }
    public void setOutput(Object output) { this.output = output; }
}
