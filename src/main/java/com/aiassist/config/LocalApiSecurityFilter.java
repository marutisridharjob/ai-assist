package com.aiassist.config;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hardens the local REST API while authentication is not yet in place. The API
 * is bound to loopback (see {@code server.address}), but a loopback bind alone
 * does not stop a malicious web page in the user's own browser from reaching it
 * via DNS-rebinding or a cross-site request. This filter closes that gap:
 *
 * <ul>
 *   <li>rejects any request whose {@code Host} is not a loopback name — the
 *       standard defence against DNS-rebinding attacks;</li>
 *   <li>rejects browser cross-origin/cross-site requests by checking the
 *       {@code Origin} and {@code Referer} headers (non-browser clients, which
 *       send neither, are allowed);</li>
 *   <li>adds conservative security response headers on every reply.</li>
 * </ul>
 *
 * This is defence-in-depth, not a substitute for authentication, which is
 * planned separately.
 */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class LocalApiSecurityFilter extends OncePerRequestFilter {

    /** Host names that legitimately point at this machine's loopback interface. */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hostIsLoopback(request)) {
            deny(response, "Invalid Host");
            return;
        }
        if (!originIsAllowed(request)) {
            deny(response, "Cross-origin requests are not allowed");
            return;
        }
        addSecurityHeaders(response);
        chain.doFilter(request, response);
    }

    private boolean hostIsLoopback(HttpServletRequest request) {
        return LOOPBACK_HOSTS.contains(hostName(request.getHeader("Host")));
    }

    /**
     * Same-origin (or non-browser) only. A browser attaches Origin to
     * cross-site and state-changing requests and Referer to most navigations;
     * when present, the host part must be a loopback name. Requests with
     * neither header are treated as non-browser clients and allowed.
     */
    private boolean originIsAllowed(HttpServletRequest request) {
        return headerHostAllowed(request.getHeader("Origin"))
                && headerHostAllowed(request.getHeader("Referer"));
    }

    private boolean headerHostAllowed(String urlHeader) {
        if (urlHeader == null || urlHeader.isBlank() || "null".equalsIgnoreCase(urlHeader.trim())) {
            return true; // absent or opaque origin — not a cross-site browser call we can attribute
        }
        try {
            String host = java.net.URI.create(urlHeader.trim()).getHost();
            return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false; // unparseable — reject rather than guess
        }
    }

    /** Extracts the lowercased host from a Host header, dropping the port. */
    private static String hostName(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return "";
        }
        String host = hostHeader.trim();
        if (host.startsWith("[")) { // IPv6 literal, e.g. [::1]:8080
            int close = host.indexOf(']');
            return close > 0 ? host.substring(0, close + 1).toLowerCase(Locale.ROOT) : host.toLowerCase(Locale.ROOT);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
    }

    private static void deny(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(reason);
    }
}
