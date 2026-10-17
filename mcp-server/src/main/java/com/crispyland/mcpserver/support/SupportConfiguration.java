package com.crispyland.mcpserver.support;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupportProperties.class)
public class SupportConfiguration {

    @Bean
    public SupportStore supportStore(ObjectMapper mapper, SupportProperties properties) {
        return new SupportStore(mapper, properties.path());
    }
}
