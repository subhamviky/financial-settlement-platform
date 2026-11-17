package com.subhamviky.settlement.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subhamviky.e2a.identity.ClaimMapIdentityResolver;
import com.subhamviky.e2a.identity.Identity;
import com.subhamviky.e2a.identity.IdentityAtIngress;
import com.subhamviky.e2a.identity.IdentityRefused;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Who is calling (hub design note SI-01). The callers are other services: o2c-agent-service,
 * reconciliation-service and audit-ai-service. Container Apps authentication verifies each one's
 * Entra token and hands over the verified claims; e2a-java's IdentityAtIngress resolves them.
 *
 *   - X-Tenant-Id must be a tenant the caller is granted (app role "tenant:&lt;id&gt;");
 *   - the route's action must be granted: POST /settlements -> settlement.submit,
 *     POST /settlements/{id}/adjustments -> settlement.adjust, any GET -> settlement.read.
 *
 * Refused: 401 without verified claims, 403 otherwise. Without E2A_IDENTITY_SOURCE (tests, local
 * runs, the landscape demo) identity is off and the headers are taken as given, as before.
 */
@Component
public class ServiceIdentityFilter extends OncePerRequestFilter {

    public static final String IDENTITY_ATTRIBUTE = "e2a.identity";

    private static final Logger log = LoggerFactory.getLogger(ServiceIdentityFilter.class);
    private static final Pattern ADJUSTMENTS = Pattern.compile("^/api/v1/settlements/[^/]+/adjustments/?$");

    private final IdentityAtIngress ingress;
    private final ObjectMapper json;

    public ServiceIdentityFilter(IdentityAtIngress ingress, ObjectMapper json) {
        this.ingress = ingress;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ingress.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Identity identity = ingress.resolve(headers(request), request.getHeader("X-Tenant-Id"));
            ClaimMapIdentityResolver.requireRoles(identity, actionRole(request));
            request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        } catch (IdentityRefused refused) {
            log.warn("identity refused: {} {} -> {} ({})", request.getMethod(), request.getRequestURI(),
                    refused.httpStatus(), refused.reason());
            response.setStatus(refused.httpStatus());
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), Map.of("error", refused.reason()));
            return;
        }
        chain.doFilter(request, response);
    }

    /** The app role a route needs. A path not listed needs a role no caller has. */
    static String actionRole(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("GET".equals(request.getMethod())) {
            return "settlement.read";
        }
        if ("POST".equals(request.getMethod()) && path.matches("^/api/v1/settlements/?$")) {
            return "settlement.submit";
        }
        if ("POST".equals(request.getMethod()) && ADJUSTMENTS.matcher(path).matches()) {
            return "settlement.adjust";
        }
        return "settlement.unsupported-route";
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            out.put(name, request.getHeader(name));
        }
        return out;
    }
}
