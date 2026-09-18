package com.valstats.admin;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.views.ModelAndView;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Controller("/admin")
public class AdminController {

    private final AdminAuthService auth;
    private final AdminDashboardService dashboard;

    public AdminController(AdminAuthService auth, AdminDashboardService dashboard) {
        this.auth = auth;
        this.dashboard = dashboard;
    }

    @Get
    public Object index(HttpRequest<?> request) {
        if (!authenticated(request)) return HttpResponse.redirect(URI.create("/admin/login"));
        String query = request.getParameters().get("q");
        Map<String, Object> model = new HashMap<>();
        model.put("queues", dashboard.queueStatus());
        model.put("activeSyncs", dashboard.activeSyncs());
        model.put("stalledSyncs", dashboard.stalledSyncs());
        model.put("henrik", dashboard.henrikStatus());
        model.put("ipLimits", dashboard.ipRateLimitStatus());
        model.put("query", query == null ? "" : query);
        model.put("accounts", dashboard.searchAccounts(query));
        model.put("searched", query != null && !query.isBlank());
        model.put("deleted", request.getParameters().get("deleted"));
        return new ModelAndView<>("admin/dashboard", model);
    }

    @Post(uri = "/accounts/delete", consumes = MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> deleteAccount(HttpRequest<?> request, @Body("puuid") String puuid) {
        if (!authenticated(request)) return HttpResponse.redirect(URI.create("/admin/login"));
        int deleted = dashboard.deleteAccount(puuid == null ? null : puuid.trim());
        return HttpResponse.redirect(URI.create("/admin?deleted=" + deleted));
    }

    @Get("/live")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> live(HttpRequest<?> request) {
        if (!authenticated(request)) return HttpResponse.unauthorized();
        return HttpResponse.ok(dashboard.liveStatus());
    }

    @Get("/login")
    public Object login(HttpRequest<?> request) {
        if (authenticated(request)) return HttpResponse.redirect(URI.create("/admin"));
        Map<String, Object> model = new HashMap<>();
        model.put("configured", auth.configured());
        model.put("sent", request.getParameters().contains("sent"));
        model.put("error", request.getParameters().contains("error"));
        model.put("limited", request.getParameters().contains("limited"));
        model.put("codeEntry", request.getParameters().contains("sent")
                || request.getParameters().contains("error")
                || request.getParameters().contains("limited"));
        return new ModelAndView<>("admin/login", model);
    }

    @Post(uri = "/code", consumes = MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> requestCode(@Body("email") String email) {
        return switch (auth.requestCode(email)) {
            case SENT -> HttpResponse.redirect(URI.create("/admin/login?sent=1"));
            case RATE_LIMITED -> HttpResponse.redirect(URI.create("/admin/login?limited=1"));
            case NOT_CONFIGURED, DELIVERY_FAILED -> HttpResponse.redirect(URI.create("/admin/login?error=1"));
        };
    }

    @Post(uri = "/verify", consumes = MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> verify(@Body("code") String code) {
        String token = auth.verifyCode(code == null ? null : code.trim());
        if (token == null) return HttpResponse.redirect(URI.create("/admin/login?error=1"));
        Cookie cookie = Cookie.of(AdminAuthService.SESSION_COOKIE, token)
                .httpOnly(true).secure(true).sameSite(SameSite.Strict)
                .path("/").maxAge(auth.sessionTtlSeconds());
        return HttpResponse.redirect(URI.create("/admin")).cookie(cookie);
    }

    @Post(uri = "/logout", consumes = MediaType.APPLICATION_FORM_URLENCODED)
    public MutableHttpResponse<?> logout(HttpRequest<?> request) {
        String token = sessionToken(request);
        auth.logout(token);
        Cookie expired = Cookie.of(AdminAuthService.SESSION_COOKIE, "")
                .httpOnly(true).secure(true).sameSite(SameSite.Strict)
                .path("/").maxAge(0);
        return HttpResponse.redirect(URI.create("/admin/login")).cookie(expired);
    }

    private boolean authenticated(HttpRequest<?> request) {
        return auth.isAuthenticated(sessionToken(request));
    }

    private String sessionToken(HttpRequest<?> request) {
        return request.getCookies().findCookie(AdminAuthService.SESSION_COOKIE)
                .map(Cookie::getValue).orElse(null);
    }

}
