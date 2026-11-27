package com.subhamviky.settlement.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One correlation id per request, in this service's log too (GCP-02 Phase B, item 3). The caller's X-Correlation-Id is
 * kept (or one is made), put in the logging MDC, returned in the response header, and written on one JSON line per
 * request with the service name, path, status and time: the same line the Python services write, so one query in
 * Cloud Logging follows a run through all five services.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationLogFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";
    private static final Logger log = LoggerFactory.getLogger(CorrelationLogFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        String id = given == null || given.isBlank() ? UUID.randomUUID().toString()
                : given.substring(0, Math.min(given.length(), 100)).replaceAll("[\"\\\\\\p{Cntrl}]", "_");
        long started = System.currentTimeMillis();
        MDC.put("correlation_id", id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("{\"event\":\"http_request\",\"service\":\"settlement-service\",\"correlation_id\":\"{}\","
                            + "\"method\":\"{}\",\"path\":\"{}\",\"status\":{},\"ms\":{}}",
                    id, request.getMethod(), request.getRequestURI().replace("\"", ""), response.getStatus(),
                    System.currentTimeMillis() - started);
            MDC.remove("correlation_id");
        }
    }
}
