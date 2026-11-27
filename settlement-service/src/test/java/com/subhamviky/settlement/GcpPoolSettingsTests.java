package com.subhamviky.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** The Google Cloud profile fails fast when the ledger database is unreachable (GCP-02 Phase B, drill 1). */
class GcpPoolSettingsTests {

    private Properties gcp() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-gcp.yml"));
        return yaml.getObject();
    }

    @Test
    void aRequestWaitsAtMostFiveSecondsForAConnectionNotThirty() {
        assertThat(gcp().getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
    }

    @Test
    void thePoolStaysSmallForTheOneSmallLedgerVm() {
        assertThat(gcp().getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("3");
    }
}
