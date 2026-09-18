package com.valstats.admin;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("admin.auth")
public class AdminAuthConfiguration {

    private String email = "";
    private String fromEmail = "";
    private int codeTtlSeconds = 600;
    private int sessionTtlSeconds = 28_800;
    private int resendCooldownSeconds = 60;

    public String getEmail() {
        return runtimeValue("ADMIN_EMAIL", email);
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFromEmail() {
        return runtimeValue("ADMIN_FROM_EMAIL", fromEmail);
    }

    public void setFromEmail(String fromEmail) {
        this.fromEmail = fromEmail;
    }

    public int getCodeTtlSeconds() {
        return codeTtlSeconds;
    }

    public void setCodeTtlSeconds(int value) {
        this.codeTtlSeconds = value;
    }

    public int getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(int value) {
        this.sessionTtlSeconds = value;
    }

    public int getResendCooldownSeconds() {
        return resendCooldownSeconds;
    }

    public void setResendCooldownSeconds(int value) {
        this.resendCooldownSeconds = value;
    }

    public boolean isConfigured() {
        return !getEmail().isBlank() && !getFromEmail().isBlank();
    }

    private String runtimeValue(String environmentName, String configuredValue) {
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null || environmentValue.isBlank()
                ? configuredValue == null ? "" : configuredValue
                : environmentValue;
    }
}
