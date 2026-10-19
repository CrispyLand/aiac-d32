package com.crispyland.mcpserver.gatekeeper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "gatekeeper.enabled", havingValue = "true")
@EnableConfigurationProperties(GatekeeperTeacherProperties.class)
public class GatekeeperTeacherConfiguration {
}
