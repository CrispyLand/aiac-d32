package com.crispyland.web.gatekeeper;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatekeeperProperties.class)
class GatekeeperConfiguration {

    @Bean
    @ConditionalOnProperty(name = "gatekeeper.enabled", havingValue = "true")
    GatekeeperEventLog gatekeeperEventLog(GatekeeperProperties properties) {
        Path dataDir = Paths.get(properties.dataDir());
        return new GatekeeperEventLog(dataDir);
    }

    @Bean
    @ConditionalOnProperty(name = "gatekeeper.enabled", havingValue = "true")
    QuizPool quizPool() {
        return QuizPool.loadAll();
    }
}
