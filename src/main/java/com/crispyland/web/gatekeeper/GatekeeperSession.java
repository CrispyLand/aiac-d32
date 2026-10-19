package com.crispyland.web.gatekeeper;

import com.crispyland.web.gatekeeper.GatekeeperEvent.EventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * In-memory snapshot of a Gatekeeper session, derived from the event log.
 * Can always be rebuilt from {@code events.jsonl}.
 */
public class GatekeeperSession {

    private String sessionId;
    private String studentId;
    private int currentGate;
    private List<Integer> passedGates = new ArrayList<>();
    private String businessSummary = "";
    private String lastJudgeNextFocus = "";
    private Map<Integer, List<TimestampedMessage>> conversations = new LinkedHashMap<>();
    private int eventCount;
    private String lastHash = "";
    private Instant createdAt;
    private Instant lastActivityAt;

    public record TimestampedMessage(String role, String content, Instant timestamp, String source) {
        /** Constructor for LLM-generated messages. */
        public TimestampedMessage(String role, String content, Instant timestamp) {
            this(role, content, timestamp, "llm");
        }
    }

    /** Rebuild session state from the event log. */
    public static GatekeeperSession fromEvents(List<GatekeeperEvent> events) {
        GatekeeperSession session = new GatekeeperSession();
        for (GatekeeperEvent event : events) {
            session.applyEvent(event);
        }
        return session;
    }

    private void applyEvent(GatekeeperEvent event) {
        eventCount = event.seq();
        lastHash = event.hash();
        lastActivityAt = event.timestamp();

        switch (event.type()) {
            case SESSION_STARTED -> {
                sessionId = event.sessionId();
                studentId = event.studentId();
                currentGate = 0;
                createdAt = event.timestamp();
            }
            case STUDENT_MESSAGE -> {
                String content = textField(event.payload(), "content");
                addMessage(event.gate(), "STUDENT", content, event.timestamp(), "student");
            }
            case TUTOR_MESSAGE -> {
                String content = textField(event.payload(), "content");
                String source = textField(event.payload(), "source");
                addMessage(event.gate(), "TUTOR", content, event.timestamp(),
                        source != null ? source : "llm");
            }
            case GATE_UNLOCKED -> {
                int gate = event.gate();
                if (!passedGates.contains(gate)) {
                    passedGates.add(gate);
                }
                String entry = textField(event.payload(), "businessSummaryEntry");
                if (entry != null && !entry.isBlank()) {
                    if (!businessSummary.isEmpty()) {
                        businessSummary += "\n\n";
                    }
                    businessSummary += entry;
                }
                currentGate = gate + 1;
            }
            case JUDGE_RESULT -> {
                JsonNode payload = event.payload();
                if (payload != null && payload.has("nextFocus")) {
                    lastJudgeNextFocus = payload.get("nextFocus").asText("");
                }
            }
            case GATE_FAILED -> {
                // nextFocus may be updated from the judge result that preceded this
            }
            default -> {
                // Other events don't affect snapshot state
            }
        }
    }

    private void addMessage(int gate, String role, String content, Instant timestamp, String source) {
        conversations.computeIfAbsent(gate, k -> new ArrayList<>())
                .add(new TimestampedMessage(role, content, timestamp, source));
    }

    private String textField(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        return node.get(field).asText(null);
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getSessionId() { return sessionId; }
    public String getStudentId() { return studentId; }
    public int getCurrentGate() { return currentGate; }
    public List<Integer> getPassedGates() { return passedGates; }
    public String getBusinessSummary() { return businessSummary; }
    public String getLastJudgeNextFocus() { return lastJudgeNextFocus; }
    public Map<Integer, List<TimestampedMessage>> getConversations() { return conversations; }
    public int getEventCount() { return eventCount; }
    public String getLastHash() { return lastHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }

    /** Get conversation for the current gate. */
    public List<TimestampedMessage> currentConversation() {
        return conversations.getOrDefault(currentGate, List.of());
    }

    /** Format conversation for LLM prompts: "ROLE [timestamp]: content" */
    public String formatConversation(int gate) {
        List<TimestampedMessage> messages = conversations.getOrDefault(gate, List.of());
        StringBuilder sb = new StringBuilder();
        for (TimestampedMessage msg : messages) {
            sb.append(msg.role()).append(" [").append(msg.timestamp()).append("]: ")
                    .append(msg.content()).append("\n");
        }
        return sb.toString();
    }

    /** Get all STUDENT messages from a specific gate's conversation. */
    public List<String> studentMessages(int gate) {
        return conversations.getOrDefault(gate, List.of()).stream()
                .filter(m -> "STUDENT".equals(m.role()))
                .map(TimestampedMessage::content)
                .toList();
    }

    /** Compute word count: blank → 0, otherwise split on whitespace. */
    public static int wordCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    // ── Setters for deserialization ──────────────────────────────────────

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setCurrentGate(int currentGate) { this.currentGate = currentGate; }
    public void setPassedGates(List<Integer> passedGates) { this.passedGates = passedGates; }
    public void setBusinessSummary(String businessSummary) { this.businessSummary = businessSummary; }
    public void setLastJudgeNextFocus(String lastJudgeNextFocus) { this.lastJudgeNextFocus = lastJudgeNextFocus; }
    public void setConversations(Map<Integer, List<TimestampedMessage>> conversations) { this.conversations = conversations; }
    public void setEventCount(int eventCount) { this.eventCount = eventCount; }
    public void setLastHash(String lastHash) { this.lastHash = lastHash; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }
}
