package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.workers.profesores.chat.client.AcademiaClient;
import com.workers.profesores.chat.model.openai.ToolCall;
import com.workers.profesores.chat.model.openai.ToolOutput;
import org.springframework.stereotype.Service;

@Service
public class ToolsDispatcher {
    public com.fasterxml.jackson.databind.JsonNode getAlumnos() {
        return academiaClient.listAlumnos(1, 1000, null);
    }
    public int getTotalAlumnos() {
        JsonNode alumnos = academiaClient.listAlumnos(1, 1000, null);
        return (alumnos != null && alumnos.isArray()) ? alumnos.size() : 0;
    }
    private final AcademiaClient academiaClient;

    public ToolsDispatcher(AcademiaClient academiaClient) {
        this.academiaClient = academiaClient;
    }

    public ToolOutput execute(ToolCall call) {
        switch (call.getName()) {
            case "getAlumnoById": {
                String id = call.getArgs().get("id").asText();
                JsonNode alumno = academiaClient.getAlumnoById(id);
                return ToolOutput.of(call, alumno);
            }
            case "listAlumnos": {
                // Obtener todos los alumnos (sin paginación real)
                JsonNode allAlumnos = academiaClient.listAlumnos(1, 1000, null);
                java.util.List<JsonNode> alumnosList = new java.util.ArrayList<>();
                if (allAlumnos != null && allAlumnos.isArray()) {
                    for (JsonNode n : allAlumnos) alumnosList.add(n);
                }
                // Filtro por nombre
                String q = call.getArgs().has("q") ? call.getArgs().get("q").asText("") : "";
                if (!q.isEmpty()) {
                    alumnosList = alumnosList.stream().filter(a -> a.get("nombre").asText("").toLowerCase().contains(q.toLowerCase())).toList();
                }
                // Parámetros flexibles
                int limit = call.getArgs().has("limit") ? call.getArgs().get("limit").asInt(0) : 0;
                int offset = call.getArgs().has("offset") ? call.getArgs().get("offset").asInt(0) : 0;
                int from = call.getArgs().has("from") ? call.getArgs().get("from").asInt(0) : 0;
                int to = call.getArgs().has("to") ? call.getArgs().get("to").asInt(0) : 0;
                int last = call.getArgs().has("last") ? call.getArgs().get("last").asInt(0) : 0;
                int start = 0;
                int end = alumnosList.size();
                if (from > 0 && to > 0 && from <= to && to <= alumnosList.size()) {
                    start = from - 1;
                    end = to;
                } else if (last > 0 && last <= alumnosList.size()) {
                    start = alumnosList.size() - last;
                    end = alumnosList.size();
                } else {
                    if (offset > 0) start = offset;
                    if (limit > 0) end = Math.min(start + limit, alumnosList.size());
                }
                if (start < 0) start = 0;
                if (end > alumnosList.size()) end = alumnosList.size();
                if (start > end) start = end;
                java.util.List<JsonNode> result = alumnosList.subList(start, end);
                com.fasterxml.jackson.databind.node.ArrayNode arrayNode = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
                arrayNode.addAll(result);
                return ToolOutput.of(call, arrayNode);
            }
            default:
                return ToolOutput.of(call, "Tool no implementada");
        }
    }
}
