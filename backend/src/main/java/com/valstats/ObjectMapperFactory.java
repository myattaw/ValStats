package com.valstats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

@Factory
public class ObjectMapperFactory {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

}

