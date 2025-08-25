package com.workers.profesores.chat.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class RequestFlowXmlContext {
    private static final Map<String, RequestFlowXmlLogger> contextMap = new ConcurrentHashMap<>();

    public static void set(String requestId, RequestFlowXmlLogger logger) {
        contextMap.put(requestId, logger);
    }

    public static RequestFlowXmlLogger get(String requestId) {
        return contextMap.get(requestId);
    }

    public static void remove(String requestId) {
        contextMap.remove(requestId);
    }
}
