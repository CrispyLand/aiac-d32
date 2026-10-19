package com.crispyland.web.gatekeeper;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * A single event in the append-only Gatekeeper event log.
 * Hash chain: each event's {@code hash} = SHA-256 of canonical JSON (all fields except hash,
 * keys in alphabetical order). {@code prevHash} = previous event's hash (empty string for first).
 */
public record GatekeeperEvent(
        int seq,
        String eventId,
        String sessionId,
        String studentId,
        Instant timestamp,
        int gate,
        EventType type,
        JsonNode payload,
        String prevHash,
        String hash) {

    public enum EventType {
        SESSION_STARTED,
        SESSION_RESUMED,
        STUDENT_MESSAGE,
        STUDENT_INTEGRITY_SIGNAL,
        TUTOR_MESSAGE,
        GUARD_DECISION,
        PASTE_ATTEMPT,
        GATE_SUBMITTED,
        JUDGE_RESULT,
        GATE_UNLOCKED,
        GATE_FAILED,
        TRANSCRIPT_EXPORTED,
        TEACHER_ANALYSIS_GENERATED,
        TEACHER_DECISION
    }
}
