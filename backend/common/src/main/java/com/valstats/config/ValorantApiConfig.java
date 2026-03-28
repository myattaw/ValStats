package com.valstats.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/**
 * Configuration for Valorant API settings.
 * Reads from application.yml under the "valorant" namespace.
 */
@Singleton
@ConfigurationProperties("valorant")
public class ValorantApiConfig {

    @Value("${valorant.api-key:}")
    private String apiKey;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

}

