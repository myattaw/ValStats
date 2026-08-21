package com.valstats.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Options;

/**
 * Handles browser preflight requests forwarded through API Gateway's default
 * route. API Gateway adds the configured CORS response headers.
 */
@Controller
public class CorsController {

    @Options("/{+path}")
    public HttpResponse<Void> preflight() {
        return HttpResponse.ok();
    }
}
