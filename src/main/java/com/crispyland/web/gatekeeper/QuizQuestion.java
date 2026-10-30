package com.crispyland.web.gatekeeper;

import java.util.List;

/**
 * A single question in a gate's quiz pool.
 * Loaded from {@code gateN-quiz-pool.json} classpath resources.
 */
public record QuizQuestion(
        String id,
        String type,           // "multiple_choice", "true_false", "matching"
        List<String> criteria, // C1-C5 this question targets
        String concept,
        String stem,
        List<Option> options,  // for multiple_choice
        Object correct,        // String for MC ("b"), Boolean for T/F (false)
        String explanation,
        List<Pair> pairs,      // for matching
        String textbookRef     // e.g. "See pages 33–47 on business structures"
) {
    public record Option(String key, String text) {}
    public record Pair(String left, String right) {}
}
