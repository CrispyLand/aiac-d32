package com.crispyland.mcpserver.gatekeeper;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gatekeeper")
public record GatekeeperTeacherProperties(
        boolean enabled,
        String internalUrl,
        String internalToken) {

    public GatekeeperTeacherProperties {
        if (internalUrl == null) internalUrl = "http://localhost:8080";
        if (internalToken == null) internalToken = "";
    }
}
