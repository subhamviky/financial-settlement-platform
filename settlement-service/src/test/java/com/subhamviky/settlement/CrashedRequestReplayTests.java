package com.subhamviky.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subhamviky.settlement.settlement.ClearingGateway;
import com.subhamviky.settlement.settlement.Settlement;
import com.subhamviky.settlement.settlement.SimulatedClearingGateway;
import com.subhamviky.settlement.settlement.StuckSettlementRecovery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Backlog G-2, end to end: the process "dies" during clearing (after T1 committed), the client's
 * retry is refused as still in progress, recovery finishes the settlement, and the same retry then
 * replays the outcome: one posting, one charge.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrashedRequestReplayTests {

    static final BigDecimal CRASH_AMOUNT = new BigDecimal("777.77");

    /** Stands for the process dying: an Error, which no retry, saga or orchestrator handler catches. */
    static class SimulatedCrash extends Error {
        SimulatedCrash() { super("simulated crash during clearing"); }
    }

    @TestConfiguration
    static class CrashingClearing {
        @Bean
        @Primary
        ClearingGateway crashingClearing(SimulatedClearingGateway real) {
            return new ClearingGateway() {
                private boolean crashed;
                @Override
                public boolean confirm(Settlement s) {
                    if (s.getAmount().compareTo(CRASH_AMOUNT) == 0 && !crashed) {
                        crashed = true;                       // dies once; asked again, it answers
                        throw new SimulatedCrash();
                    }
                    return real.confirm(s);
                }
            };
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private StuckSettlementRecovery recovery;

    private MvcResult submit(String key) throws Exception {
        String body = """
                {"payerAccount":"ACC-1000","payeeAccount":"ACC-2000","amount":%s,"currency":"EUR"}""".formatted(CRASH_AMOUNT);
        return mvc.perform(post("/api/v1/settlements").header("X-Tenant-Id", "acme").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    void aRetryAfterACrashReplaysTheOutcomeThatRecoveryRecorded() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> submit(key)).hasRootCauseInstanceOf(SimulatedCrash.class);

        MvcResult stillOpen = submit(key);                    // before recovery: the record is unfinished
        assertThat(stillOpen.getResponse().getStatus()).isEqualTo(409);

        recovery.recover(Instant.now().plusSeconds(1));

        MvcResult replay = submit(key);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        JsonNode s = json.readTree(replay.getResponse().getContentAsString());
        assertThat(s.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(s.get("amount").decimalValue()).isEqualByComparingTo(CRASH_AMOUNT);
    }
}
