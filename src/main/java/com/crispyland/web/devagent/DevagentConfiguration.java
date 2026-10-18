package com.crispyland.web.devagent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DevagentProperties.class)
class DevagentConfiguration {}
