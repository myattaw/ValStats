package com.valstats.client;

import com.valstats.config.ValorantApiConfig;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

@Singleton
public class HenrikApiKeyProvider {
    private final SecretsManagerClient secretsManagerClient;
    private final ValorantApiConfig localConfig;
    private final String secretArn;
    private volatile String cachedKey;

    public HenrikApiKeyProvider(
            SecretsManagerClient secretsManagerClient,
            ValorantApiConfig localConfig,
            @Value("${henrik-api.secret-arn:}") String secretArn
    ) {
        this.secretsManagerClient = secretsManagerClient;
        this.localConfig = localConfig;
        this.secretArn = secretArn;
    }

    public String getApiKey() {
        String localKey = localConfig.getApiKey();
        if (localKey != null && !localKey.isBlank()) {
            return localKey;
        }
        if (cachedKey == null) {
            synchronized (this) {
                if (cachedKey == null) {
                    if (secretArn == null || secretArn.isBlank()) {
                        throw new IllegalStateException("HDEV_KEY or henrik-api.secret-arn must be configured");
                    }
                    cachedKey = secretsManagerClient.getSecretValue(GetSecretValueRequest.builder()
                                    .secretId(secretArn)
                                    .build())
                            .secretString();
                }
            }
        }
        return cachedKey;
    }
}
