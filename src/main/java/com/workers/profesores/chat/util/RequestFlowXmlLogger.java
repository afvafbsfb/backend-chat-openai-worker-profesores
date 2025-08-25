package com.workers.profesores.chat.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class RequestFlowXmlLogger {
    private final List<Step> steps = new ArrayList<>();
    private final String testName;
    private final String basePath;

    public RequestFlowXmlLogger(String testName, String basePath) {
        this.testName = testName;
        this.basePath = basePath;
    }

    public void addStep(String name, String description) {
        steps.add(new Step(name, description, LocalDateTime.now()));
    }

    public void writeHtml() {
        String fileName = basePath + File.separator + testName + ".html";
        try (FileWriter writer = new FileWriter(fileName, false)) {
            writer.write("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<meta charset=\"UTF-8\">\n<title>Flujo de la prueba: " + escapeHtml(testName) + "</title>\n");
            writer.write("<style>body{font-family:sans-serif;} table{border-collapse:collapse;width:100%;margin-top:1em;} th,td{border:1px solid #ccc;padding:8px;text-align:left;} th{background:#f0f0f0;} tr:nth-child(even){background:#fafafa;} .step{margin-bottom:1em;} .timestamp{color:#888;font-size:0.9em;}</style>\n");
            writer.write("</head><body>\n<h2>Flujo de la prueba: " + escapeHtml(testName) + "</h2>\n<table>\n<thead><tr><th>Paso</th><th>Descripción</th><th>Timestamp</th></tr></thead>\n<tbody>\n");
            int i = 1;
            for (Step step : steps) {
                writer.write("<tr class='step'><td>" + i + ". " + escapeHtml(step.name) + "</td><td>" + escapeHtml(step.description) + "</td><td class='timestamp'>" + step.timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")) + "</td></tr>\n");
                i++;
            }
            writer.write("</tbody></table>\n</body></html>\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static class Step {
        String name;
        String description;
        LocalDateTime timestamp;
        Step(String name, String description, LocalDateTime timestamp) {
            this.name = name;
            this.description = description;
            this.timestamp = timestamp;
        }
    }
}
