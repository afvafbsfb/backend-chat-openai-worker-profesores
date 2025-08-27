package com.workers.profesores.chat.logging;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class RequestFlowXmlLogger {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String BASE_DIR = "documentacion/flows";

    public synchronized void addStep(String who, String description) {
        String flowId = currentFlowId();
        try {
            Files.createDirectories(new File(BASE_DIR).toPath());
            File f = new File(BASE_DIR, flowId + ".xml");
            boolean exists = f.exists();
            try (FileOutputStream fos = new FileOutputStream(f, true);
                 OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                if (!exists) {
                    w.write("<flow id=\"" + escape(flowId) + "\">\n");
                    w.write("  <started at=\"" + escape(now()) + "\"/>\n");
                }
                w.write("  <step at=\"" + escape(now()) + "\" who=\"" + escape(who) + "\">");
                w.write(escape(description));
                w.write("</step>\n");
            }
        } catch (IOException e) {
            // swallow: logging no debe romper negocio
        }
    }

    public synchronized void finish() {
        String flowId = currentFlowId();
        File f = new File(BASE_DIR, flowId + ".xml");
        if (!f.exists()) return;
        try (FileOutputStream fos = new FileOutputStream(f, true);
             OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            w.write("  <finished at=\"" + escape(now()) + "\"/>\n");
            w.write("</flow>\n");
        } catch (IOException ignored) {}
        // render HTML “rápido”
        renderHtml(flowId);
    }

    private void renderHtml(String flowId) {
        try {
            File xml = new File(BASE_DIR, flowId + ".xml");
            File html = new File(BASE_DIR, flowId + ".html");
            String xmlText = Files.readString(xml.toPath(), StandardCharsets.UTF_8);
            String body = xmlText
                    .replace("<flow", "<div class='flow'")
                    .replace("</flow>", "</div>")
                    .replace("<step", "<div class='step'")
                    .replace("</step>", "</div>")
                    .replace("<started", "<div class='started'")
                    .replace("</started>", "</div>")
                    .replace("<finished", "<div class='finished'")
                    .replace("</finished>", "</div>");

            String tpl = """
                    <html><head><meta charset=\"utf-8\"><title>%s</title>
                    <style>
                      body{font-family:Inter,Arial,sans-serif;margin:24px}
                      .step{padding:8px 12px;margin:6px 0;border:1px solid #e5e7eb;border-radius:8px;background:#fafafa}
                      .started,.finished{color:#374151;margin:8px 0;font-weight:600}
                      .who{font-weight:600}
                    </style></head><body>
                    <h2>Flujo: %s</h2>
                    %s
                    </body></html>
                    """.formatted(flowId, flowId, body);
            Files.writeString(html.toPath(), tpl, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    private String now() { return LocalDateTime.now().format(FMT); }
    private String currentFlowId() {
        String id = MDC.get(FlowCorrelationFilter.MDC_KEY);
        return (id == null || id.isBlank()) ? "flow-unknown" : id;
    }
    private static String escape(String s){
        return s == null ? "" : s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}
