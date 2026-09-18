package com.valstats.admin;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Factory
public class AdminAwsConfiguration {

    @Singleton
    SesV2Client sesV2Client() {
        return SesV2Client.builder().build();
    }

}
