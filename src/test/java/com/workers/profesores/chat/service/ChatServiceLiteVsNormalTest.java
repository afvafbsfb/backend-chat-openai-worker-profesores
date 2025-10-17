package com.workers.profesores.chat.service;

import com.workers.profesores.chat.dto.ChatRequest;
import com.workers.profesores.chat.dto.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compare normal second turn vs lite: we instrument by capturing the size of the followup messages payload.
 * We stub the OpenAI service twice: once for normal flow (model receives full items), and once for lite
 * (descriptor only with response_format). We assert that lite followup string length is smaller and envelope is valid.
 */
public class ChatServiceLiteVsNormalTest {

    @Test
    public void compareLiteVsNormalFollowupSize() throws Exception {
        // Arrange spy to capture messages
        OpenAICallApiService openai = Mockito.spy(new OpenAICallApiService());
        ApiProxyService apiProxy = Mockito.mock(ApiProxyService.class);
        com.workers.profesores.chat.auth.JwtDelegationService jwt = Mockito.mock(com.workers.profesores.chat.auth.JwtDelegationService.class);
        ChatService chat = new ChatService(openai, apiProxy, jwt);
        // Enable lite, disable fastpath
    var fDebug = ChatService.class.getDeclaredField("debug"); fDebug.setAccessible(true); fDebug.set(chat, true);
    var fLite = ChatService.class.getDeclaredField("secondTurnLiteEnabled"); fLite.setAccessible(true); fLite.set(chat, true);
        var fFast = ChatService.class.getDeclaredField("fastpathEnabled"); fFast.setAccessible(true); fFast.set(chat, false);
        Mockito.when(jwt.createDelegatedAuthorizationHeader(Mockito.any())).thenReturn("Bearer delegated-token");
        Mockito.when(openai.getEndpointByName(Mockito.eq("usuarios.listar")))
               .thenReturn(Map.of(
                   "operationId","usuarios.listar",
                   "name","usuarios.listar",
                   "method","GET",
                   "path","/usuarios",
                   "paginated", true
               ));

        // First turn returns a tool_call
        String first = "{\n  \"choices\": [ { \n    \"message\": { \n      \"tool_calls\": [ { \n        \"id\": \"call_1\", \n        \"function\": { \n          \"name\": \"call_api\", \n          \"arguments\": \"{\\\"name\\\":\\\"usuarios.listar\\\",\\\"method\\\":\\\"GET\\\"}\" \n        } \n      } ] \n    } } ] \n}";
        Mockito.doReturn(first).when(openai).callChatWithTools(Mockito.anyList(), Mockito.any(), Mockito.any());

        // API result with 20 items (ensure valid JSON, not double-escaped)
        StringBuilder apiBuilder = new StringBuilder();
        apiBuilder.append("{\"items\":[");
        for (int i=1;i<=20;i++) {
            if (i>1) apiBuilder.append(",");
            apiBuilder.append("{\"id\":"+i+",\"nombre\":\"Usuario "+i+"\",\"email\":\"u"+i+"@ejemplo.com\"}");
        }
        apiBuilder.append("],\"page\":1,\"size\":20,\"has_more\":true,\"next_page\":2}");
        String apiResult20 = apiBuilder.toString();
    Mockito.when(apiProxy.executeSpecCall(Mockito.anyMap(), Mockito.eq("GET"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.any())).thenReturn(apiResult20);
    Mockito.when(apiProxy.executeSpecCall(Mockito.anyMap(), Mockito.eq("GET"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString())).thenReturn(apiResult20);

    // Capture sizes
    AtomicInteger liteSize = new AtomicInteger(0);
    AtomicInteger normalSize = new AtomicInteger(0);
        // Second call stub: return minimal lite content
        String secondLite = "{\n  \"choices\": [ { \n    \"message\": { \n      \"content\": \"{\\\"text\\\":\\\"Usuarios listados.\\\",\\\"suggestions\\\":[\\\"Siguiente página\\\"],\\\"summary_fields\\\":[\\\"nombre\\\"]}\" \n    } } ] \n}";
        Mockito.doAnswer(inv -> {
            // Capture followup for lite
            List<Map<String,Object>> msgsLite = inv.getArgument(0);
            Map<String,Object> extras = inv.getArgument(1);
            int size = stringify(msgsLite).length();
            liteSize.set(size);
            System.out.println("[TEST] lite_followup_chars=" + size);
            System.out.println("[TEST] lite_extras=" + extras);
            return secondLite;
        }).when(openai).callChatWithToolsWithExtras(Mockito.anyList(), Mockito.anyMap(), Mockito.any(), Mockito.any());

        // Execute chat (should take lite path)
        List<ChatRequest.Message> msgs = List.of(new ChatRequest.Message("user","Dame los usuarios"));
        ResponseEnvelope out = chat.runChat(msgs, null, "Bearer original", new com.workers.profesores.chat.auth.UserClaims(1L, List.of("Admin_plataforma"), 1, null));
        assertEquals("success", out.getStatus());
        assertEquals("usuarios", out.getData().getType());
        assertEquals(20, out.getData().getItems().size());
    System.out.println("[TEST][LiteVsNormal] LITE data.type=" + out.getData().getType());

        // Now force normal path by disabling lite
        fLite.set(chat, false);
        // Stub normal second call to return JSON with text only (to be parsed)
        String normalSecond = "{\n  \"choices\": [ { \n    \"message\": { \n      \"content\": \"{\\\"text\\\":\\\"Usuarios listados (normal).\\\"}\" \n    } } ] \n}";
        Mockito.reset(openai); // keep spy
    Mockito.when(apiProxy.executeSpecCall(Mockito.anyMap(), Mockito.eq("GET"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.any())).thenReturn(apiResult20);
    Mockito.when(apiProxy.executeSpecCall(Mockito.anyMap(), Mockito.eq("GET"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString())).thenReturn(apiResult20);
        Mockito.when(jwt.createDelegatedAuthorizationHeader(Mockito.any())).thenReturn("Bearer delegated-token");
        Mockito.when(openai.getEndpointByName(Mockito.eq("usuarios.listar")))
               .thenReturn(Map.of(
                   "operationId","usuarios.listar",
                   "name","usuarios.listar",
                   "method","GET",
                   "path","/usuarios",
                   "paginated", true
               ));
        Mockito.doAnswer(inv -> {
            List<Map<String,Object>> msgs2 = inv.getArgument(0);
            boolean hasTool = false;
            for (Object mObj : msgs2) {
                if (mObj instanceof Map<?,?> m && "tool".equals(String.valueOf(m.get("role")))) { hasTool = true; break; }
            }
            if (!hasTool) {
                return first; // first turn
            }
            int size = stringify(msgs2).length();
            normalSize.set(size);
            System.out.println("[TEST] normal_followup_chars=" + size);
            return normalSecond;
        }).when(openai).callChatWithTools(Mockito.anyList(), Mockito.any(), Mockito.any());

        // Run again to hit normal followup (which re-injects items in the assistant echo + tool outputs)
        ResponseEnvelope outNormal = chat.runChat(List.of(new ChatRequest.Message("user","Dame los usuarios")), null, "Bearer original", new com.workers.profesores.chat.auth.UserClaims(1L, List.of("Admin_plataforma"), 1, null));
        assertEquals("success", outNormal.getStatus());
        assertEquals("usuarios", outNormal.getData().getType());
        assertEquals(20, outNormal.getData().getItems().size());
    System.out.println("[TEST][LiteVsNormal] NORMAL data.type=" + outNormal.getData().getType());

    // Compare sizes (lite must be strictly smaller)
    assertTrue(liteSize.get() > 0, "lite size should be >0");
    assertTrue(normalSize.get() > 0, "normal size should be >0");
    System.out.println("[TEST] lite_vs_normal_ratio=" + (liteSize.get()*1.0/normalSize.get()));
    assertTrue(liteSize.get() < normalSize.get(), "lite followup payload should be smaller than normal");
    }

    // stringify helper for debugging size
    private static String stringify(Object o) {
        if (o == null) return "null";
        try { return o.toString(); } catch (Exception e) { return "<?>"; }
    }
}
