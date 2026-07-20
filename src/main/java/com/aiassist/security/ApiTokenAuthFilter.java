package com.aiassist.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.aiassist.config.SecurityProperties;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a valid bearer token on every API request. Runs just after
 * {@link com.aiassist.config.LocalApiSecurityFilter} (host/origin checks), so
 * only same-machine, same-origin callers that also hold the shared token can
 * reach the controllers. The token is presented either as
 * {@code Authorization: Bearer <token>} or an {@code X-API-Token} header.
 *
 * <p>This is the first step of authentication: a locally-generated shared
 * secret. It can be extended later (per-client tokens, user login) without
 * changing the callers' header contract.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SecurityProperties props;
    private final ApiTokenService tokenService;

    public ApiTokenAuthFilter(SecurityProperties props, ApiTokenService tokenService) {
        this.props = props;
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!props.tokenRequired()) {
            chain.doFilter(request, response);
            return;
        }
        String presented = presentedToken(request);
        if (!tokenService.matches(presented)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Missing or invalid API token");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String presentedToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return auth.substring(BEARER_PREFIX.length()).strip();
        }
        return request.getHeader("X-API-Token");
    }
}
