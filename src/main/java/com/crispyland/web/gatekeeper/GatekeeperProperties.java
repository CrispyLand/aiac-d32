package com.crispyland.web.gatekeeper;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gatekeeper")
public record GatekeeperProperties(
        boolean enabled,
        String locale,
        String dataDir,
        String accessCode,
        String teacherSecret,
        String internalToken,
        int chatRatePerMinute,
        int submitRatePerMinute,
        int pasteRatePerMinute,
        boolean logPasteExcerpt,
        boolean quizEnabled,
        int quizRemedialCount,
        int quizReinforcementCount) {

    public GatekeeperProperties {
        if (quizRemedialCount <= 0) quizRemedialCount = 3;
        if (quizReinforcementCount <= 0) quizReinforcementCount = 3;
    }
}
