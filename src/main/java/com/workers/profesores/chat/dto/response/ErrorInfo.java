package com.workers.profesores.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorInfo {
    private String code;
    private String details;

    public ErrorInfo() {}
    public ErrorInfo(String code, String details) {
        this.code = code;
        this.details = details;
    }
    public static ErrorInfo of(String code, String details) { return new ErrorInfo(code, details); }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
}
