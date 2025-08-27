package com.workers.profesores.chat.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class FlowCorrelationFilter implements Filter {
    public static final String FLOW_HEADER = "X-Flow-Id";
    public static final String MDC_KEY = "flowId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String flowId = null;
        if (request instanceof HttpServletRequest http) {
            flowId = http.getHeader(FLOW_HEADER);
        }
        if (flowId == null || flowId.isBlank()) {
            flowId = "flow-" + UUID.randomUUID();
        }
        MDC.put(MDC_KEY, flowId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
