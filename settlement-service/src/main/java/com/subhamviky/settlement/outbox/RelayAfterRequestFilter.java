package com.subhamviky.settlement.outbox;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Google Cloud (hub design note GCP-01): relays the outbox at the end of each request that changes
 * state, while the request still has CPU. Cloud Run scales to zero and allocates CPU only during
 * requests, so the 2-second scheduled relay cannot be relied on between requests; the scheduled
 * maintenance job (MaintenanceController) is the safety net. A failure here is logged, never
 * returned to the caller: the business change is already committed and the event stays in the
 * outbox for the next relay. Enabled by settlement.outbox.relay-after-request=true.
 */
@Component
@ConditionalOnProperty(prefix = "settlement.outbox", name = "relay-after-request", havingValue = "true")
public class RelayAfterRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RelayAfterRequestFilter.class);

    private final OutboxRelay relay;

    public RelayAfterRequestFilter(OutboxRelay relay) {
        this.relay = relay;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        try {
            int published = relay.relay();
            if (published > 0) {
                log.debug("relayed {} outbox event(s) after {}", published, request.getRequestURI());
            }
        } catch (RuntimeException e) {
            log.warn("outbox relay after request failed; the scheduled job will publish: {}", e.toString());
        }
    }
}
