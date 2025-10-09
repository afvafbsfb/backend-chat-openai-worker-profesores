package com.workers.profesores.chat.model.openai;

import java.util.Map;

public class ToolSchema {
    private String type = "function";
    private String name;
    private String description;
    private Map<String, Object> parameters;

    public ToolSchema() {}
    public ToolSchema(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }
    public String getType() { return type; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getParameters() { return parameters; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
}
