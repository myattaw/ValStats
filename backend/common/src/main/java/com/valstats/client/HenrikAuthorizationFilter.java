package com.valstats.client;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

@Singleton
@Filter(patterns = "/**", serviceId = "https://api.henrikdev.xyz")
public class HenrikAuthorizationFilter implements HttpClientFilter {
    private final HenrikApiKeyProvider apiKeyProvider;

    public HenrikAuthorizationFilter(HenrikApiKeyProvider apiKeyProvider) {
        this.apiKeyProvider = apiKeyProvider;
    }

    @Override
    public Publisher<? extends HttpResponse<?>> doFilter(
            MutableHttpRequest<?> request,
            ClientFilterChain chain
    ) {
        request.header("Authorization", apiKeyProvider.getApiKey());
        return chain.proceed(request);
    }
}
