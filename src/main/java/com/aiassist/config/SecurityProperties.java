package com.aiassist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security settings for the local REST API. By default the API requires a
 * bearer token that the app generates on first run and stores in a
 * user-private file (see {@link com.aiassist.security.ApiTokenService}); any
 * client — including the desktop UI, were it to call over HTTP — reads the
 * token from that shared file. A fixed token can be supplied instead (e.g. for
 * tests or scripted use), and the requirement can be turned off entirely.
 */
@ConfigurationProperties(prefix = "ai-assist.security")
public record SecurityProperties(String apiToken, Boolean apiTokenRequired, String tokenFile) {

    public SecurityProperties {
        if (apiToken == null) {
            apiToken = "";
        }
        if (apiTokenRequired == null) {
            apiTokenRequired = Boolean.TRUE;
        }
        if (tokenFile == null) {
            tokenFile = "";
        }
    }

    /** True when API calls must present a valid token. */
    public boolean tokenRequired() {
        return Boolean.TRUE.equals(apiTokenRequired);
    }
}
