package com.workers.profesores.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HierarchyInfo {
    private JsonNode parent;
    private List<JsonNode> children;

    public HierarchyInfo() {}
    public HierarchyInfo(JsonNode parent, List<JsonNode> children) { this.parent = parent; this.children = children; }
    public JsonNode getParent() { return parent; }
    public void setParent(JsonNode parent) { this.parent = parent; }
    public List<JsonNode> getChildren() { return children; }
    public void setChildren(List<JsonNode> children) { this.children = children; }
}
