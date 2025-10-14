package com.workers.profesores.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSection {
    private String type;
    private List<JsonNode> items = new ArrayList<>();
    private PaginationInfo pagination;
    private HierarchyInfo hierarchy;

    public DataSection() {}
    public DataSection(String type, List<JsonNode> items, PaginationInfo pagination, HierarchyInfo hierarchy) {
        this.type = type;
        if (items != null) this.items = items; else this.items = new ArrayList<>();
        this.pagination = pagination;
        this.hierarchy = hierarchy;
    }
    public static DataSection of(String type, List<JsonNode> items, PaginationInfo pagination) { return new DataSection(type, items, pagination, null); }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public List<JsonNode> getItems() { return items; }
    public void setItems(List<JsonNode> items) { this.items = items; }
    public PaginationInfo getPagination() { return pagination; }
    public void setPagination(PaginationInfo pagination) { this.pagination = pagination; }
    public HierarchyInfo getHierarchy() { return hierarchy; }
    public void setHierarchy(HierarchyInfo hierarchy) { this.hierarchy = hierarchy; }
}
