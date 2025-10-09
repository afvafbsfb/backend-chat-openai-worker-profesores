package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SpecLoaderService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest = new RestTemplate();

    @Value("${served.openapi.url:}")
    private String servedOpenapiUrl;

    @Value("${served.openapi.failOnInvalid:false}")
    private boolean failOnInvalid;

    // critical endpoints (path + method) that we expect x-permissions for.
    private final List<Map.Entry<String,String>> critical = List.of(
            Map.entry("/usuarios","get"),
            Map.entry("/academias","get")
    );

    public List<Map<String,Object>> getWhitelist() {
        try {
            JsonNode root = loadSpec();
            if (root == null) return List.of();
            JsonNode paths = root.get("paths");
            if (paths == null || !paths.fieldNames().hasNext()) {
                System.out.println("[SpecLoader] No paths found in served-openapi.json");
                if (failOnInvalid) throw new RuntimeException("served-openapi.json has no paths");
                return List.of();
            }
            List<Map<String,Object>> out = new ArrayList<>();
            Iterator<String> pathIt = paths.fieldNames();
            Pattern pathParamRe = Pattern.compile("\\{([^}]+)\\}");
            while (pathIt.hasNext()) {
                String rawPath = pathIt.next();
                // Normalize: remove trailing slashes so "/usuarios/" -> "/usuarios"
                String path = rawPath.replaceAll("/+$", "");
                if (path.isEmpty()) path = "/";
                JsonNode methods = paths.get(rawPath);
                Iterator<String> methIt = methods.fieldNames();
                while (methIt.hasNext()) {
                    String method = methIt.next();
                    JsonNode op = methods.get(method);
                    String operationId = op.has("operationId") ? op.get("operationId").asText() : (method + " " + path);
                    String description = op.has("summary") ? op.get("summary").asText() : (op.has("description") ? op.get("description").asText() : "");
                    List<String> pathParams = new ArrayList<>();
                    Matcher m = pathParamRe.matcher(path);
                    while (m.find()) pathParams.add(m.group(1));
                    List<String> queryParams = new ArrayList<>();
                    JsonNode parameters = op.get("parameters");
                    if (parameters != null && parameters.isArray()) {
                        for (JsonNode pnode : parameters) {
                            if (pnode.has("in") && "query".equals(pnode.get("in").asText()) && pnode.has("name")) {
                                queryParams.add(pnode.get("name").asText());
                            }
                        }
                    }
                    List<String> body = new ArrayList<>();
                    JsonNode requestBody = op.get("requestBody");
                    if (requestBody != null && requestBody.has("content")) {
                        JsonNode content = requestBody.get("content");
                        JsonNode appJson = content.get("application/json");
                        if (appJson != null && appJson.has("schema")) {
                            JsonNode schema = appJson.get("schema");
                            // Resolve $ref to components/schemas if present
                            schema = resolveRefIfNeeded(schema, root);
                            // If schema is an array with items that are $ref, resolve items
                            if (schema != null && schema.has("type") && "array".equals(schema.get("type").asText()) && schema.has("items")) {
                                JsonNode items = resolveRefIfNeeded(schema.get("items"), root);
                                if (items != null && items.has("properties")) {
                                    JsonNode props = items.get("properties");
                                    if (props != null && props.isObject()) {
                                        Iterator<String> f = props.fieldNames();
                                        while (f.hasNext()) body.add(f.next());
                                    }
                                }
                            } else {
                                JsonNode props = schema == null ? null : schema.get("properties");
                                if (props != null && props.isObject()) {
                                    Iterator<String> f = props.fieldNames();
                                    while (f.hasNext()) body.add(f.next());
                                }
                            }
                        }
                    }
                    Map<String,Object> endpoint = new HashMap<>();
                    endpoint.put("name", operationId);
                    endpoint.put("operationId", operationId);
                    endpoint.put("method", method.toUpperCase());
                    endpoint.put("path", path);
                    endpoint.put("description", description);
                    endpoint.put("pathParams", pathParams);
                    endpoint.put("query", queryParams);
                    endpoint.put("body", body);
                    if (op.has("x-permissions")) {
                        endpoint.put("x-permissions", mapper.convertValue(op.get("x-permissions"), new TypeReference<Map<String,Object>>(){}));
                    }
                    out.add(endpoint);
                }
            }
            for (Map.Entry<String,String> e : critical) {
                boolean found = out.stream().anyMatch(ep -> {
                    String p = String.valueOf(ep.getOrDefault("path",""));
                    String mth = String.valueOf(ep.getOrDefault("method",""));
                    return p.equals(e.getKey()) && mth.equalsIgnoreCase(e.getValue());
                });
                if (!found) {
                    System.out.println("[SpecLoader] Critical endpoint not found: " + e.getKey() + " " + e.getValue());
                    if (failOnInvalid) throw new RuntimeException("Critical endpoint missing: " + e.getKey());
                }
            }
            return out;
        } catch (Exception ex) {
            System.out.println("[SpecLoader] Error loading spec: " + ex.getMessage());
            if (failOnInvalid) throw new RuntimeException(ex);
            return List.of();
        }
    }

    private JsonNode loadSpec() {
        try {
            if (servedOpenapiUrl != null && !servedOpenapiUrl.isBlank()) {
                System.out.println("[SpecLoader] Loading served-openapi.json from URL: " + servedOpenapiUrl);
                String txt = rest.getForObject(servedOpenapiUrl, String.class);
                if (txt == null) return null;
                return mapper.readTree(txt);
            }
            ClassPathResource r = new ClassPathResource("served-openapi.json");
            if (r.exists()) {
                try (InputStream is = r.getInputStream()) {
                    return mapper.readTree(is);
                }
            }
            System.out.println("[SpecLoader] No served-openapi.json found on classpath and no URL configured");
            return null;
        } catch (Exception ex) {
            System.out.println("[SpecLoader] Exception reading spec: " + ex.getMessage());
            if (failOnInvalid) throw new RuntimeException(ex);
            return null;
        }
    }

    // Resolve a local $ref (like "#/components/schemas/MyType") to the referenced JsonNode inside the spec
    private JsonNode resolveRefIfNeeded(JsonNode node, JsonNode root) {
        if (node == null) return null;
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            // Only handle local refs of the form #/components/schemas/Name
            if (ref.startsWith("#/components/schemas/")) {
                String name = ref.substring("#/components/schemas/".length());
                JsonNode comps = root.get("components");
                if (comps != null) {
                    JsonNode schemas = comps.get("schemas");
                    if (schemas != null) {
                        JsonNode resolved = schemas.get(name);
                        if (resolved != null) return resolved;
                    }
                }
            }
            // Fallback: try to resolve via naive pointer split
            if (ref.startsWith("#/")) {
                String[] parts = ref.substring(2).split("/");
                JsonNode cur = root;
                for (String p : parts) {
                    if (cur == null) break;
                    cur = cur.get(p);
                }
                return cur;
            }
        }
        return node;
    }
}
