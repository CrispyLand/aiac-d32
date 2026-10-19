package com.crispyland.web.gatekeeper;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatekeeperProperties.class)
class GatekeeperConfiguration {}
