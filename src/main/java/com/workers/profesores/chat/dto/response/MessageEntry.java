package com.workers.profesores.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageEntry {
    private String type; // info | warning | system | debug
    private String content;

    public MessageEntry() {}
    public MessageEntry(String type, String content) {
        this.type = type;
        this.content = content;
    }
    public static MessageEntry of(String type, String content) { return new MessageEntry(type, content); }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
