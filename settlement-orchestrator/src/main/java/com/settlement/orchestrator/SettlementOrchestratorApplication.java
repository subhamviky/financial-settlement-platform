package com.settlement.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableSync;

@SpringBootApplication
@EnableSync
public class SettlementOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SettlementOrchestratorApplication.class, args);
    }
}