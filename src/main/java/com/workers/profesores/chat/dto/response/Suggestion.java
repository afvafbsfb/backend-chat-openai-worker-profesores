package com.workers.profesores.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Suggestion {
    private String id;
    private String displayText;
    // "Paginacion" | "Registro" | "Generica"
    private String type;
    // Solo si type="Registro": "Alta" | "Baja" | "Modificacion"
    private String recordAction;
    private RecordRef record; // Solo si type="Registro"
    private PaginationSuggestion pagination; // Solo si type="Paginacion"
    private String contextToken; // Requerido en Paginacion

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordRef {
        private String resource;
        private String id;
        public RecordRef() {}
        public RecordRef(String resource, String id) { this.resource = resource; this.id = id; }
        public String getResource() { return resource; }
        public void setResource(String resource) { this.resource = resource; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PaginationSuggestion {
        // "next" | "prev" | "goto"
        private String direction;
        private Integer page;
        private Integer size;
        public PaginationSuggestion() {}
        public PaginationSuggestion(String direction, Integer page, Integer size) { this.direction = direction; this.page = page; this.size = size; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
        public Integer getPage() { return page; }
        public void setPage(Integer page) { this.page = page; }
        public Integer getSize() { return size; }
        public void setSize(Integer size) { this.size = size; }
    }

    public Suggestion() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisplayText() { return displayText; }
    public void setDisplayText(String displayText) { this.displayText = displayText; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getRecordAction() { return recordAction; }
    public void setRecordAction(String recordAction) { this.recordAction = recordAction; }
    public RecordRef getRecord() { return record; }
    public void setRecord(Suggestion.RecordRef record) { this.record = record; }
    public PaginationSuggestion getPagination() { return pagination; }
    public void setPagination(PaginationSuggestion pagination) { this.pagination = pagination; }
    public String getContextToken() { return contextToken; }
    public void setContextToken(String contextToken) { this.contextToken = contextToken; }
}
