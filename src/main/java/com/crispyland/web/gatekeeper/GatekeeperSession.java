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
    private Map<Integer, List<QuizAttempt>> quizHistory = new LinkedHashMap<>();
    private boolean pendingQuiz;
    private int pendingQuizSince;
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
                pendingQuiz = false;
            }
            case JUDGE_RESULT -> {
                JsonNode payload = event.payload();
                if (payload != null && payload.has("nextFocus")) {
                    lastJudgeNextFocus = payload.get("nextFocus").asText("");
                }
            }
            case GATE_FAILED -> {
                pendingQuiz = true;
                pendingQuizSince = event.seq();
            }
            case QUIZ_PRESENTED -> {
                // Quiz shown to student — pendingQuiz remains true until completed
            }
            case QUIZ_COMPLETED -> {
                int gate = event.gate();
                if (gate == currentGate && "remedial".equals(textField(event.payload(), "trigger"))
                        && event.payload().path("presentationSeq").asInt(Integer.MAX_VALUE) > pendingQuizSince) {
                    pendingQuiz = false;
                }
                JsonNode payload = event.payload();
                if (payload != null) {
                    String quizId = textField(payload, "quizId");
                    String trigger = textField(payload, "trigger");
                    int score = payload.has("score") ? payload.get("score").intValue() : 0;
                    int total = payload.has("total") ? payload.get("total").intValue() : 0;
                    List<QuizAttempt.QuizAnswer> answers = new ArrayList<>();
                    for (JsonNode answer : payload.path("answers")) {
                        answers.add(new QuizAttempt.QuizAnswer(answer.path("questionId").asText(),
                                answer.path("studentAnswer").asText(), answer.path("correct").asBoolean(),
                                answer.path("concept").asText(), answer.path("textbookRef").asText()));
                    }
                    QuizAttempt attempt = new QuizAttempt(quizId, gate, trigger, answers, score, total, event.timestamp());
                    quizHistory.computeIfAbsent(gate, k -> new ArrayList<>()).add(attempt);
                }
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
    public Map<Integer, List<QuizAttempt>> getQuizHistory() { return quizHistory; }
    public boolean isPendingQuiz() { return pendingQuiz; }
    public int getPendingQuizSince() { return pendingQuizSince; }
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

    /** Get a summary of the last quiz for a gate, for inclusion in the tutor prompt. */
    public String getLastQuizSummary(int gate) {
        List<QuizAttempt> attempts = quizHistory.getOrDefault(gate, List.of());
        if (attempts.isEmpty()) return "None.";
        QuizAttempt last = attempts.getLast();
        String review = last.answers().stream().filter(a -> !a.correct())
                .map(a -> (a.concept() == null ? "Review this topic" : a.concept()).replace('_', ' ')
                        + (a.textbookRef() == null || a.textbookRef().isBlank() ? "" : " (" + a.textbookRef() + ")"))
                .distinct().collect(java.util.stream.Collectors.joining(", "));
        return "The student completed a concept quiz. They scored " + last.score() + "/" + last.total() + "."
                + (review.isBlank() ? "" : " Review concepts: " + review + ".");
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
    public void setQuizHistory(Map<Integer, List<QuizAttempt>> quizHistory) { this.quizHistory = quizHistory; }
    public void setPendingQuiz(boolean pendingQuiz) { this.pendingQuiz = pendingQuiz; }
    public void setPendingQuizSince(int pendingQuizSince) { this.pendingQuizSince = pendingQuizSince; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }
}
