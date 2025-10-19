package com.workers.profesores.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseEnvelope {
    private String status; // success | error
    private String message; // mensaje principal
    private DataSection data; // null si error
    // Reemplaza suggestions (legacy). Nueva estructura tipada:
    private List<Suggestion> uiSuggestions = new ArrayList<>();
    private Integer uiSuggestionsVersion; // opcional para versionado
    private ErrorInfo error; // solo si status=error
    private List<MessageEntry> messages = new ArrayList<>();

    public ResponseEnvelope() {}

    public static ResponseEnvelope success(String message, DataSection data, List<Suggestion> uiSuggestions, List<MessageEntry> msgs) {
        ResponseEnvelope r = new ResponseEnvelope();
        r.status = "success";
        r.message = message;
        r.data = data;
        if (uiSuggestions != null) r.uiSuggestions = uiSuggestions; else r.uiSuggestions = new ArrayList<>();
        if (msgs != null) r.messages = msgs; else r.messages = new ArrayList<>();
        return r;
    }

    public static ResponseEnvelope error(String message, String code, String details) {
        ResponseEnvelope r = new ResponseEnvelope();
        r.status = "error";
        r.message = message;
        r.error = new ErrorInfo(code, details);
        return r;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public DataSection getData() { return data; }
    public void setData(DataSection data) { this.data = data; }
    public List<Suggestion> getUiSuggestions() { return uiSuggestions; }
    public void setUiSuggestions(List<Suggestion> uiSuggestions) { this.uiSuggestions = uiSuggestions; }
    public Integer getUiSuggestionsVersion() { return uiSuggestionsVersion; }
    public void setUiSuggestionsVersion(Integer uiSuggestionsVersion) { this.uiSuggestionsVersion = uiSuggestionsVersion; }
    public ErrorInfo getError() { return error; }
    public void setError(ErrorInfo error) { this.error = error; }
    public List<MessageEntry> getMessages() { return messages; }
    public void setMessages(List<MessageEntry> messages) { this.messages = messages; }
}
