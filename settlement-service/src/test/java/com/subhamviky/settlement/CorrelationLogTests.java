package com.subhamviky.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/** The caller's correlation id is kept, returned and logged on one line per request (GCP-02 Phase B). */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.com.subhamviky.settlement.api.CorrelationLogFilter=INFO")
class CorrelationLogTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void theCallersIdIsReturnedAndLogged(CapturedOutput output) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "run-42");
        headers.set("X-Tenant-Id", "acme");
        var response = rest.exchange("/api/v1/accounts/ACC-1000/balance", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("run-42");
        assertThat(output.getAll()).contains("\"correlation_id\":\"run-42\"").contains("\"service\":\"settlement-service\"")
                .contains("/api/v1/accounts/ACC-1000/balance");
    }

    @Test
    void aRequestWithoutOneGetsOne() {
        var response = rest.getForEntity("/api/v1/accounts/ACC-1000/balance", String.class);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }
}
