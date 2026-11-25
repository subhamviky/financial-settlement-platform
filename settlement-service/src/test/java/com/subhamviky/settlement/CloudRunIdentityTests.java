package com.subhamviky.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.subhamviky.e2a.identity.IdentityAtIngress;
import com.subhamviky.e2a.identity.IdentitySettings;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * GCP-01 (E2A v1.11.0): on Cloud Run, IAM verifies the caller's Google ID token and the filter
 * reads it from Authorization. The token names only an email; the deployment grants each caller
 * its tenants and action roles (E2A_IDENTITY_SUBJECT_ROLES).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class CloudRunIdentityTests {

    static final String O2C = "o2c-app@demo.iam.gserviceaccount.com";
    static final String AUDIT = "audit-app@demo.iam.gserviceaccount.com";

    @TestConfiguration
    static class CloudRunIdentityOn {
        @Bean
        @Primary
        IdentityAtIngress cloudRunIdentity() {
            return new IdentityAtIngress(IdentitySettings.from(Map.of(
                    "E2A_IDENTITY_SOURCE", "gcp_cloud_run",
                    "E2A_IDENTITY_TENANTS_FROM_ROLES", "tenant:",
                    "E2A_IDENTITY_TRUSTED_ISSUERS", "[\"https://accounts.google.com\"]",
                    "E2A_IDENTITY_SUBJECT_ROLES", "{\"" + O2C + "\": [\"tenant:acme\", \"settlement.submit\"],"
                            + " \"" + AUDIT + "\": [\"tenant:acme\", \"settlement.read\"]}")::get));
        }
    }

    @Autowired private MockMvc mvc;

    static String bearer(String email) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String payload = "{\"iss\":\"https://accounts.google.com\",\"aud\":\"https://settlement.a.run.app\",\"email\":\"" + email + "\"}";
        return "Bearer " + b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
    }

    private int submit(String email, String tenant) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/v1/settlements").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payerAccount\":\"ACC-1000\",\"payeeAccount\":\"ACC-2000\",\"amount\":10.00,\"currency\":\"EUR\"}");
        if (email != null) {
            req.header("Authorization", bearer(email));
        }
        return mvc.perform(req.header("X-Tenant-Id", tenant)).andReturn().getResponse().getStatus();
    }

    @Test
    void theServiceAccountCloudRunVerifiedIsServedForWhatItIsGranted() throws Exception {
        assertThat(submit(O2C, "acme")).isEqualTo(201);
        assertThat(submit(AUDIT, "acme")).isEqualTo(403);                    // granted reads only
        assertThat(mvc.perform(get("/api/v1/settlements/" + UUID.randomUUID()).header("Authorization", bearer(AUDIT))
                .header("X-Tenant-Id", "acme")).andReturn().getResponse().getStatus()).isIn(200, 404);
    }

    @Test
    void noTokenAnotherTenantOrAStrangerIsRefused() throws Exception {
        assertThat(submit(null, "acme")).isEqualTo(401);
        assertThat(submit(O2C, "globex")).isEqualTo(403);
        assertThat(submit("stranger@else.example", "acme")).isEqualTo(403);
    }
}
