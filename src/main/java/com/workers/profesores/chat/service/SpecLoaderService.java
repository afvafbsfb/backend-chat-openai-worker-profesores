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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class SpecLoaderService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest = new RestTemplate();
    private static final Logger logger = LoggerFactory.getLogger(SpecLoaderService.class);

    @Value("${served.openapi.url:}")
    private String servedOpenapiUrl;

    @Value("${served.openapi.failOnInvalid:false}")
    private boolean failOnInvalid;

    // critical endpoints (path + method) that we expect x-permissions for.
    // Include all mutation operations (POST, PUT, PATCH, DELETE) as they are equally or more critical
    private final List<Map.Entry<String,String>> critical = List.of(
            // Usuarios
            Map.entry("/usuarios","get"),
            Map.entry("/usuarios","post"),
            // Academias
            Map.entry("/academias","get"),
            Map.entry("/academias","post"),
            // Tarifas
            Map.entry("/tarifas","get"),
            Map.entry("/tarifas","post")
            // Note: PUT/PATCH/DELETE with path params like /usuarios/{id} are checked dynamically below
    );

    public List<Map<String,Object>> getWhitelist() {
        try {
            JsonNode root = loadSpec();
            if (root == null) return List.of();
            JsonNode paths = root.get("paths");
            if (paths == null || !paths.fieldNames().hasNext()) {
                logger.warn("[SpecLoader] No paths found in served-openapi.json");
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
                    // Detailed metadata for query params (type/default/minimum/maximum/description)
                    List<Map<String,Object>> queryDetails = new ArrayList<>();
                    JsonNode parameters = op.get("parameters");
                    if (parameters != null && parameters.isArray()) {
                        for (JsonNode pnode : parameters) {
                            if (pnode.has("in") && "query".equals(pnode.get("in").asText()) && pnode.has("name")) {
                                String pname = pnode.get("name").asText();
                                queryParams.add(pname);
                                try {
                                    JsonNode schemaNode = pnode.get("schema");
                                    JsonNode resolvedSchema = resolveRefIfNeeded(schemaNode, root);
                                    Map<String,Object> qd = new HashMap<>();
                                    qd.put("name", pname);
                                    if (resolvedSchema != null && resolvedSchema.has("type")) qd.put("type", resolvedSchema.get("type").asText());
                                    if (resolvedSchema != null && resolvedSchema.has("default")) qd.put("default", resolvedSchema.get("default"));
                                    if (resolvedSchema != null && resolvedSchema.has("minimum")) qd.put("minimum", resolvedSchema.get("minimum"));
                                    if (resolvedSchema != null && resolvedSchema.has("maximum")) qd.put("maximum", resolvedSchema.get("maximum"));
                                    if (pnode.has("description")) qd.put("description", pnode.get("description").asText());
                                    queryDetails.add(qd);
                                } catch (Exception e) {
                                    // ignore metadata extraction errors and continue
                                }
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
                    if (!queryDetails.isEmpty()) endpoint.put("queryDetails", queryDetails);
                    endpoint.put("body", body);
                    if (op.has("x-permissions")) {
                        endpoint.put("x-permissions", mapper.convertValue(op.get("x-permissions"), new TypeReference<Map<String,Object>>(){}));
                    }
                    // Detect paginated responses: look at 200 response schema for totalElements/items or page/size
                    boolean paginated = false;
                    try {
                        JsonNode responses = op.get("responses");
                        if (responses != null && responses.has("200")) {
                            JsonNode r200 = responses.get("200");
                            if (r200 != null && r200.has("content")) {
                                JsonNode appJson = r200.path("content").path("application/json");
                                if (appJson != null && appJson.has("schema")) {
                                    JsonNode respSchema = resolveRefIfNeeded(appJson.get("schema"), root);
                                    if (respSchema != null && respSchema.has("properties")) {
                                        JsonNode props = respSchema.get("properties");
                                        if (props.has("totalElements") || props.has("items") || props.has("page") || props.has("size")) {
                                            paginated = true;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    if (paginated) endpoint.put("paginated", true);
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
                    logger.warn("[SpecLoader] Critical endpoint not found: {} {}", e.getKey(), e.getValue());
                    if (failOnInvalid) throw new RuntimeException("Critical endpoint missing: " + e.getKey());
                }
            }
            return out;
        } catch (Exception ex) {
            logger.error("[SpecLoader] Error loading spec: {}", ex.getMessage());
            if (failOnInvalid) throw new RuntimeException(ex);
            return List.of();
        }
    }

    private JsonNode loadSpec() {
        try {
            // 1) Try URL if configured; on failure or empty response, fallback to classpath resource
            if (servedOpenapiUrl != null && !servedOpenapiUrl.isBlank()) {
                try {
                    logger.info("[SpecLoader] Loading served-openapi.json from URL: {}", servedOpenapiUrl);
                    String txt = rest.getForObject(servedOpenapiUrl, String.class);
                    if (txt != null) {
                        JsonNode node = mapper.readTree(txt);
                        if (failOnInvalid && node != null) {
                            try {
                                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                                byte[] digest = md.digest(txt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                StringBuilder hs = new StringBuilder();
                                for (int i = 0; i < 4 && i < digest.length; i++) hs.append(String.format("%02x", digest[i]));
                                logger.debug("[SpecLoader] loaded spec from URL checksum_sha4={} size_bytes={}", hs.toString(), txt.length());
                            } catch (Exception ignore) { }
                        }
                        if (node != null) return node;
                        logger.warn("[SpecLoader] URL returned empty/invalid body, falling back to classpath served-openapi.json");
                    } else {
                        logger.warn("[SpecLoader] URL returned null body, falling back to classpath served-openapi.json");
                    }
                } catch (Exception e) {
                    logger.warn("[SpecLoader] Failed to load from URL ({}). Falling back to classpath. reason={}", servedOpenapiUrl, e.getMessage());
                    // continue to classpath fallback
                }
            }

            // 2) Strict classpath fallback: only look for served-openapi.json on classpath
            ClassPathResource r = new ClassPathResource("served-openapi.json");
            if (r.exists()) {
                try (InputStream is = r.getInputStream()) {
                    byte[] all = is.readAllBytes();
                    String txt = new String(all, java.nio.charset.StandardCharsets.UTF_8);
                    if (failOnInvalid && txt != null) {
                        try {
                            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                            byte[] digest = md.digest(txt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            StringBuilder hs = new StringBuilder();
                            for (int i = 0; i < 4 && i < digest.length; i++) hs.append(String.format("%02x", digest[i]));
                            logger.debug("[SpecLoader] loaded spec from classpath checksum_sha4={} size_bytes={}", hs.toString(), all.length);
                        } catch (Exception ignore) { }
                    }
                    return mapper.readTree(txt);
                }
            }
            logger.warn("[SpecLoader] No served-openapi.json found on classpath and no URL configured");
            return null;
        } catch (Exception ex) {
            logger.error("[SpecLoader] Exception reading spec: {}", ex.getMessage());
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
