// Archivo archivado: CollectionAggregateController.java
// Copia del original para mantener historial tras la limpieza.
package com.workers.profesores.chat.aggregate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workers.profesores.chat.service.ApiProxyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;


import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.workers.profesores.chat.logging.RequestFlowXmlLogger;

@RestController
@RequestMapping("/vlodeiro/secretaria")

@SuppressWarnings("unchecked")
public class CollectionAggregateController {

    private final ApiProxyService apiProxy;
    private final ObjectMapper om = new ObjectMapper();
    private final RequestFlowXmlLogger flow;

    public CollectionAggregateController(ApiProxyService apiProxy, RequestFlowXmlLogger flow) {
        this.apiProxy = apiProxy;
        this.flow = flow;
    }

    private static final int MAX_ALL = 1000;
    private static final int AGG_PAGE_SIZE = 200;


    @GetMapping("/{resource}")
    public ResponseEntity<ListResponse<Map<String, Object>>> list(
            @PathVariable String resource,
            @RequestParam(defaultValue = "paged") String mode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam Map<String, String> query
    ) {
        flow.addStep("Controller", "Entrada list: resource=%s, mode=%s, page=%s, size=%s, query=%s"
                .formatted(resource, mode, page, size, query));

        ResourceType rt = ResourceType.fromKey(resource)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "resource no permitido"));

        long total = estimateTotal(rt, query);
        flow.addStep("Service", "estimateTotal -> " + total);
        int p = page == null ? 0 : Math.max(page, 0);
        int s = size == null ? 50 : Math.min(Math.max(size, 1), 200);

        switch (mode) {
            case "export": {
                String url = buildExportUrl(resource, query);
                flow.addStep("Controller", "mode=export -> link=" + url);
                var res = new ListResponse<Map<String,Object>>("export", total, null, null, null, "Descarga el CSV desde el enlace.", url);
                flow.finish();
                return ResponseEntity.ok(res);
            }
            case "all": {
                if (total == 0) {
                    flow.addStep("Controller", "mode=all -> total=0 (sin items)");
                    var res = new ListResponse<Map<String,Object>>("all", 0, null, null, List.of(), "No hay registros", null);
                    flow.finish();
                    return ResponseEntity.ok(res);
                }
                if (total > MAX_ALL) {
                    String url2 = buildExportUrl(resource, query);
                    flow.addStep("Controller", "mode=all -> total>"+MAX_ALL+" -> export url="+url2);
                    var res = new ListResponse<Map<String,Object>>("export", total, null, null, null,
                            "Hay " + total + " registros. Usa export para no bloquear.", url2);
                    flow.finish();
                    return ResponseEntity.ok(res);
                }
                flow.addStep("Service", "fetchAll start (pagesize="+AGG_PAGE_SIZE+")");
                List<Map<String, Object>> acc = fetchAll(rt, query, MAX_ALL);
                flow.addStep("Service", "fetchAll done: items="+acc.size());
                var res = new ListResponse<Map<String,Object>>("all", total, null, null, acc, null, null);
                flow.finish();
                return ResponseEntity.ok(res);
            }
            default: {
                flow.addStep("Service", "fetchPage p=%d s=%d".formatted(p, s));
                List<Map<String, Object>> pageItems = fetchPage(rt, query, p, s);
                flow.addStep("Service", "fetchPage done: items="+pageItems.size());
                var res = new ListResponse<Map<String,Object>>("paged", total, p, s, pageItems, null, null);
                flow.finish();
                return ResponseEntity.ok(res);
            }
        }
    }

    // --- helpers para paginación y agregación ---
    private List<Map<String, Object>> fetchPage(ResourceType rt, Map<String, String> query, int page, int size) {
        try {
            Map<String, Object> pathParams = Map.of();
            Map<String, Object> q = new HashMap<>(query);
            if (rt.isPaginated()) {
                q.put(rt.getPageParam(), page);
                q.put(rt.getSizeParam(), size);
            }
            String result = apiProxy.executeWhitelistedCall(
                    Map.of("path", rt.getAcademiaPath()), "GET",
                    om.valueToTree(pathParams), om.valueToTree(q), null
            );
            JsonNode node = om.readTree(result);
            return extractItemsOrArray(node);
        } catch (Exception e) {
            throw new RuntimeException("Error llamando API: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> fetchAll(ResourceType rt, Map<String, String> query, int maxAll) {
        long total = estimateTotal(rt, query);
        List<Map<String, Object>> acc = new ArrayList<>((int) Math.min(total, maxAll));
        int pages = (int) Math.ceil((double) total / AGG_PAGE_SIZE);
        for (int p = 0; p < pages; p++) {
            acc.addAll(fetchPage(rt, query, p, AGG_PAGE_SIZE));
        }
        return acc;
    }

    @GetMapping(value = "/{resource}/export", produces = "text/csv")
    public void exportCsv(
            @PathVariable String resource,
            @RequestParam Map<String, String> query,
            HttpServletResponse response
    ) throws IOException {
        ResourceType rt = ResourceType.fromKey(resource)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "resource no permitido"));

        response.setHeader("Content-Disposition", "attachment; filename=%s.csv".formatted(rt.getResourceKey()));
        Writer w = response.getWriter();

        long total = estimateTotal(rt, query);
        List<Map<String, Object>> firstBatch = rt.isPaginated()
                ? extractItems(callAcademia(rt, 0, AGG_PAGE_SIZE, query))
                : extractItemsOrArray(callAcademia(rt, 0, 0, query));

        List<String> headers = firstBatch.isEmpty()
                ? List.of("id")
                : new ArrayList<>(firstBatch.get(0).keySet());
        w.write(String.join(",", headers) + "\n");
        for (var m : firstBatch) writeCsvRow(w, headers, m);

        if (rt.isPaginated()) {
            int pages = (int) Math.ceil((double) total / AGG_PAGE_SIZE);
            for (int p = 1; p < pages; p++) {
                var items = extractItems(callAcademia(rt, p, AGG_PAGE_SIZE, query));
                for (var m : items) writeCsvRow(w, headers, m);
                w.flush();
            }
        }
        w.flush();
    }

    // ---- helpers ----
    private JsonNode callAcademia(ResourceType rt, int page, int size, Map<String, String> query) {
        try {
            Map<String, Object> pathParams = Map.of();
            Map<String, Object> q = new HashMap<>(query);
            if (rt.isPaginated()) {
                q.put(rt.getPageParam(), page);
                q.put(rt.getSizeParam(), size);
            }
            String result = apiProxy.executeWhitelistedCall(
                    Map.of("path", rt.getAcademiaPath()), "GET",
                    om.valueToTree(pathParams), om.valueToTree(q), null
            );
            return om.readTree(result);
        } catch (Exception e) {
            throw new RuntimeException("Error llamando a API academia: " + e.getMessage(), e);
        }
    }

    private long estimateTotal(ResourceType rt, Map<String, String> query) {
        JsonNode node = rt.isPaginated()
                ? callAcademia(rt, 0, 1, query)
                : callAcademia(rt, 0, 0, query);
        JsonNode total = node.get("total");
        if (total != null && total.isNumber()) return total.asLong();
        return extractItemsOrArray(node).size();
    }

    private List<Map<String, Object>> extractItems(JsonNode pageNode) {
        JsonNode arr = pageNode.get("items");
        if (arr == null || !arr.isArray()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(om.convertValue(n, Map.class));
        return out;
    }

    private List<Map<String, Object>> extractItemsOrArray(JsonNode node) {
        if (node.isArray()) {
            List<Map<String, Object>> out = new ArrayList<>();
            node.forEach(n -> out.add(om.convertValue(n, Map.class)));
            return out;
        }
        return extractItems(node);
    }

    private String buildExportUrl(String resource, Map<String, String> query) {
        StringBuilder b = new StringBuilder("/vlodeiro/secretaria/").append(resource).append("/export");
        if (!query.isEmpty()) {
            b.append("?");
            b.append(query.entrySet().stream()
                    .map(e -> e.getKey() + "=" + url(e.getValue()))
                    .reduce((a, b2) -> a + "&" + b2).orElse(""));
        }
        return b.toString();
    }

    private String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void writeCsvRow(Writer w, List<String> headers, Map<String, Object> row) throws IOException {
        List<String> vals = new ArrayList<>(headers.size());
        for (String h : headers) {
            Object v = row.getOrDefault(h, "");
            vals.add(csv(String.valueOf(v == null ? "" : v)));
        }
        w.write(String.join(",", vals) + "\n");
    }

    private String csv(String v) {
        boolean q = v.contains(",") || v.contains("\"") || v.contains("\n");
        String esc = v.replace("\"", "\"\"");
        return q ? "\"" + esc + "\"" : esc;
    }
}
