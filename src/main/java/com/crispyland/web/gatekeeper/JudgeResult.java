package com.crispyland.web.gatekeeper;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The structured JSON response from the judge LLM.
 * Deserialized from the per-gate responseSchema output.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JudgeResult(
        int gate,
        Map<String, CriterionResult> criteria,
        boolean met,
        List<String> missing,
        String nextFocus,
        String teacherNote,
        List<String> integrityFlags) {

    public record CriterionResult(boolean met, String evidence) {}
}
