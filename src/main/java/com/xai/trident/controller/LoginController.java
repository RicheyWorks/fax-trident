package com.xai.trident.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Renders the Thymeleaf-backed login page so the CSRF token can be
 * interpolated into the form. Before this controller existed, Spring Boot
 * served {@code static/login.html} as a flat resource — the {@code th:*}
 * attributes were inert and the form submitted without a {@code _csrf} field,
 * which caused Spring Security to 403 every real login attempt.
 *
 * <p>The page itself does the work: Thymeleaf's {@code ${_csrf}} expression
 * resolves the Spring-Security-managed request attribute, which triggers
 * deferred-token issuance and sets the {@code XSRF-TOKEN} cookie as a side
 * effect. We don't need to touch the token from here.
 *
 * <p>Note: the legacy {@code src/main/resources/static/login.html} file
 * remains on disk (unsupervised file-delete is disabled in this env), but is
 * unreachable: a controller mapping for {@code GET /login} takes precedence
 * over static-resource serving in Spring's dispatcher chain.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
}
