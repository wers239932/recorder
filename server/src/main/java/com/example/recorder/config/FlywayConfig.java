package com.example.recorder.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dev-friendly Flyway strategy that repairs broken local metadata before migrate.
 */
@Configuration
public class FlywayConfig {

    @Bean
    @ConditionalOnProperty(
        prefix = "recorder.flyway",
        name = "auto-repair-on-startup",
        havingValue = "true",
        matchIfMissing = true
    )
    public FlywayMigrationStrategy repairAndMigrateStrategy() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
