package com.crispyland.web.devagent;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the devagent file assistant.
 * Disabled by default — enabled only via the {@code devagent} Spring profile.
 */
@ConfigurationProperties(prefix = "devagent")
public record DevagentProperties(
        boolean enabled,
        String projectRoot,
        int maxRounds,
        int maxReadTokens,
        List<String> allowedWritePaths) {}
