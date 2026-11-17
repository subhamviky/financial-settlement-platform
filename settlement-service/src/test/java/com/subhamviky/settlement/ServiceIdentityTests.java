package com.subhamviky.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.subhamviky.e2a.identity.IdentityAtIngress;
import com.subhamviky.e2a.identity.IdentitySettings;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Hub design note SI-01: with identity on, a service caller is accepted only with verified claims
 * (Container Apps' X-MS-CLIENT-PRINCIPAL), a granted tenant and the route's action role.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceIdentityTests {

    @TestConfiguration
    static class IdentityOn {
        @Bean
        @Primary
        IdentityAtIngress identityOn() {
            return new IdentityAtIngress(IdentitySettings.from(Map.of(
                    "E2A_IDENTITY_SOURCE", "azure_container_apps",
                    "E2A_IDENTITY_TENANTS_FROM_ROLES", "tenant:",
                    "E2A_IDENTITY_TRUSTED_ISSUERS", "[\"https://login.microsoftonline.com/dir/v2.0\"]")::get));
        }
    }

    @Autowired private MockMvc mvc;

    static String principal(String appId, String... roles) {
        String r = List.of(roles).stream().map(x -> ",{\"typ\":\"roles\",\"val\":\"" + x + "\"}").collect(Collectors.joining());
        String json = "{\"auth_typ\":\"aad\",\"role_typ\":\"roles\",\"claims\":["
                + "{\"typ\":\"iss\",\"val\":\"https://login.microsoftonline.com/dir/v2.0\"},"
                + "{\"typ\":\"azp\",\"val\":\"" + appId + "\"}" + r + "]}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private int submit(String principal, String tenant) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/v1/settlements").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payerAccount\":\"ACC-1000\",\"payeeAccount\":\"ACC-2000\",\"amount\":10.00,\"currency\":\"EUR\"}");
        if (principal != null) {
            req.header("X-MS-CLIENT-PRINCIPAL", principal);
        }
        if (tenant != null) {
            req.header("X-Tenant-Id", tenant);
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    @Test
    void aCallerWithTheTenantAndTheActionIsServed() throws Exception {
        assertThat(submit(principal("o2c-app", "settlement.submit", "tenant:acme"), "acme")).isEqualTo(201);
    }

    @Test
    void withoutVerifiedClaimsItIs401() throws Exception {
        assertThat(submit(null, "acme")).isEqualTo(401);
    }

    @Test
    void aTenantNotGrantedIs403() throws Exception {
        assertThat(submit(principal("o2c-app", "settlement.submit", "tenant:globex"), "acme")).isEqualTo(403);
        assertThat(submit(principal("o2c-app", "settlement.submit", "tenant:acme"), null)).isEqualTo(403);
    }

    @Test
    void anActionNotGrantedIs403() throws Exception {
        // audit-ai may read, never submit or adjust
        String auditor = principal("audit-ai", "settlement.read", "tenant:acme");
        assertThat(submit(auditor, "acme")).isEqualTo(403);
        int adjust = mvc.perform(post("/api/v1/settlements/" + UUID.randomUUID() + "/adjustments")
                .header("X-MS-CLIENT-PRINCIPAL", auditor).header("X-Tenant-Id", "acme").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
        assertThat(adjust).isEqualTo(403);
        int read = mvc.perform(get("/api/v1/accounts/ACC-1000/balance").header("X-MS-CLIENT-PRINCIPAL", auditor)
                .header("X-Tenant-Id", "acme")).andReturn().getResponse().getStatus();
        assertThat(read).isEqualTo(200);
    }

    @Test
    void healthStaysOpen() throws Exception {
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
