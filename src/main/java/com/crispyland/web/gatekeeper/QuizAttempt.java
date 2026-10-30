package com.crispyland.web.gatekeeper;

import java.time.Instant;
import java.util.List;

/**
 * A student's completed quiz attempt, stored in the event log.
 */
public record QuizAttempt(
        String quizId,
        int gate,
        String trigger,   // "remedial" or "reinforcement"
        List<QuizAnswer> answers,
        int score,
        int total,
        Instant completedAt
) {
    public record QuizAnswer(String questionId, String studentAnswer, boolean correct,
                             String concept, String textbookRef) {}
}
