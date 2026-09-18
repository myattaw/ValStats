package com.valstats.admin;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Singleton
public class AdminAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAuthService.class);
    public static final String SESSION_COOKIE = "valstats_admin";
    private static final Map<String, AttributeValue> CODE_KEY = Map.of(
            "PK", AttributeValue.fromS("ADMIN#AUTH"),
            "SK", AttributeValue.fromS("CODE"));

    private final DynamoDbClient dynamo;
    private final SesV2Client ses;
    private final AdminAuthConfiguration config;
    private final String tableName;
    private final SecureRandom random = new SecureRandom();

    public AdminAuthService(DynamoDbClient dynamo, SesV2Client ses, AdminAuthConfiguration config,
                            @Value("${dynamodb.table-name}") String tableName) {
        this.dynamo = dynamo;
        this.ses = ses;
        this.config = config;
        this.tableName = tableName;
    }

    public RequestResult requestCode(String requestedEmail) {
        if (!config.isConfigured()) return RequestResult.NOT_CONFIGURED;
        if (!emailMatches(requestedEmail, config.getEmail())) return RequestResult.SENT;
        long now = Instant.now().getEpochSecond();
        var current = dynamo.getItem(GetItemRequest.builder().tableName(tableName)
                .key(CODE_KEY).consistentRead(true).build()).item();
        long lastSentAt = number(current, "lastSentAt");
        if (lastSentAt > 0 && now - lastSentAt < config.getResendCooldownSeconds()) {
            return RequestResult.RATE_LIMITED;
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        String salt = randomToken(16);
        Map<String, AttributeValue> item = new HashMap<>(CODE_KEY);
        item.put("codeHash", AttributeValue.fromS(hash(salt + code)));
        item.put("salt", AttributeValue.fromS(salt));
        item.put("attempts", AttributeValue.fromN("0"));
        item.put("lastSentAt", AttributeValue.fromN(Long.toString(now)));
        item.put("expiresAt", AttributeValue.fromN(Long.toString(now + config.getCodeTtlSeconds())));
        dynamo.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());

        try {
            ses.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(config.getFromEmail())
                    .destination(Destination.builder().toAddresses(config.getEmail()).build())
                    .content(EmailContent.builder().simple(Message.builder()
                            .subject(Content.builder().data("Your ValStats admin code").build())
                            .body(Body.builder().text(Content.builder().data(
                                    "Your ValStats admin code is " + code + ". It expires in 10 minutes.").build()).build())
                            .build()).build())
                    .build());
        } catch (RuntimeException deliveryFailure) {
            LOG.error("Failed to deliver ValStats admin access code through SES: {}",
                    deliveryFailure.getMessage());
            dynamo.deleteItem(DeleteItemRequest.builder().tableName(tableName).key(CODE_KEY).build());
            return RequestResult.DELIVERY_FAILED;
        }
        return RequestResult.SENT;
    }

    public String verifyCode(String suppliedCode) {
        if (suppliedCode == null || !suppliedCode.matches("\\d{6}")) return null;
        long now = Instant.now().getEpochSecond();
        var item = dynamo.getItem(GetItemRequest.builder().tableName(tableName)
                .key(CODE_KEY).consistentRead(true).build()).item();
        if (item.isEmpty() || number(item, "expiresAt") <= now || number(item, "attempts") >= 5) return null;
        String expected = string(item, "codeHash");
        String actual = hash(string(item, "salt") + suppliedCode);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            dynamo.updateItem(UpdateItemRequest.builder().tableName(tableName).key(CODE_KEY)
                    .updateExpression("SET attempts = attempts + :one")
                    .expressionAttributeValues(Map.of(":one", AttributeValue.fromN("1"))).build());
            return null;
        }

        dynamo.deleteItem(DeleteItemRequest.builder().tableName(tableName).key(CODE_KEY).build());
        String token = randomToken(32);
        Map<String, AttributeValue> session = new HashMap<>();
        session.put("PK", AttributeValue.fromS("ADMIN#AUTH"));
        session.put("SK", AttributeValue.fromS("SESSION#" + hash(token)));
        session.put("createdAt", AttributeValue.fromN(Long.toString(now)));
        session.put("expiresAt", AttributeValue.fromN(Long.toString(now + config.getSessionTtlSeconds())));
        dynamo.putItem(PutItemRequest.builder().tableName(tableName).item(session).build());
        return token;
    }

    public boolean isAuthenticated(String token) {
        if (token == null || token.isBlank()) return false;
        var item = dynamo.getItem(GetItemRequest.builder().tableName(tableName)
                .key(sessionKey(token)).consistentRead(true).build()).item();
        return !item.isEmpty() && number(item, "expiresAt") > Instant.now().getEpochSecond();
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            dynamo.deleteItem(DeleteItemRequest.builder().tableName(tableName).key(sessionKey(token)).build());
        }
    }

    public int sessionTtlSeconds() {
        return config.getSessionTtlSeconds();
    }

    public boolean configured() {
        return config.isConfigured();
    }

    private boolean emailMatches(String supplied, String configured) {
        if (supplied == null || configured == null) return false;
        byte[] suppliedBytes = supplied.trim().toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8);
        byte[] configuredBytes = configured.trim().toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(suppliedBytes, configuredBytes);
    }

    private Map<String, AttributeValue> sessionKey(String token) {
        return Map.of("PK", AttributeValue.fromS("ADMIN#AUTH"),
                "SK", AttributeValue.fromS("SESSION#" + hash(token)));
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long number(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? Long.parseLong(item.get(key).n()) : 0;
    }

    private static String string(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? item.get(key).s() : "";
    }

    public enum RequestResult {SENT, RATE_LIMITED, NOT_CONFIGURED, DELIVERY_FAILED}
}
