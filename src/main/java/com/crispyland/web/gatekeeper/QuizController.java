package com.crispyland.web.gatekeeper;

import com.crispyland.web.gatekeeper.GatekeeperEvent.EventType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Concept quiz endpoints for the Gatekeeper.
 * Quizzes are pre-authored and graded deterministically — no LLM calls.
 */
@RestController
@ConditionalOnProperty(name = "gatekeeper.enabled", havingValue = "true")
public class QuizController {

    private static final Logger log = LoggerFactory.getLogger(QuizController.class);

    private final GatekeeperEventLog eventLog;
    private final QuizPool quizPool;
    private final GatekeeperProperties properties;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, AtomicInteger> rateLimits = new ConcurrentHashMap<>();

    public QuizController(GatekeeperEventLog eventLog, QuizPool quizPool,
                           GatekeeperProperties properties) {
        this.eventLog = eventLog;
        this.quizPool = quizPool;
        this.properties = properties;
        this.mapper = JsonMapper.builder().build();
        log.info("Quiz controller enabled, pools loaded for gates: {}",
                quizPool.hasPool(0) ? "0+" : "none");
    }

    // ── Start quiz ──────────────────────────────────────────────────────

    record QuizStartRequest(String sessionId, String trigger) {}

    record QuizStartResponse(String quizId, int gate, String trigger, List<QuestionView> questions) {}

    record QuestionView(String id, String type, String stem,
                        List<QuizQuestion.Option> options,
                        List<PairView> pairs) {}

    record PairView(String left, List<String> rightChoices) {}

    @PostMapping("/api/gatekeeper/quiz/start")
    @ResponseBody
    public synchronized ResponseEntity<?> startQuiz(@RequestBody QuizStartRequest req, HttpServletRequest httpReq) {
        if (req == null || !validSessionId(req.sessionId())
                || !("remedial".equals(req.trigger()) || "reinforcement".equals(req.trigger()))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Session ID and trigger required"));
        }

        if (!properties.quizEnabled()) {
            return ResponseEntity.ok(Map.of("quizAvailable", false));
        }

        String clientIp = clientIp(httpReq);
        if (isRateLimited(clientIp, "quiz", properties.chatRatePerMinute())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded"));
        }

        GatekeeperSession session = eventLog.loadSession(req.sessionId());
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        List<GatekeeperEvent> events = eventLog.readAll(req.sessionId());
        GatekeeperEvent outcome = events.stream()
                .filter(e -> e.type() == EventType.GATE_FAILED || e.type() == EventType.GATE_UNLOCKED)
                .reduce((a, b) -> b).orElse(null);
        EventType expected = "remedial".equals(req.trigger()) ? EventType.GATE_FAILED : EventType.GATE_UNLOCKED;
        if (outcome == null || outcome.type() != expected) {
            return ResponseEntity.ok(Map.of("quizAvailable", false));
        }
        int gate = outcome.gate();
        // Reuse an unfinished presentation after refresh/restart, and present at most one quiz per outcome.
        GatekeeperEvent presented = events.stream()
                .filter(e -> e.type() == EventType.QUIZ_PRESENTED && e.seq() > outcome.seq())
                .reduce((a, b) -> b).orElse(null);
        if (presented != null) {
            boolean completed = events.stream().anyMatch(e -> e.type() == EventType.QUIZ_COMPLETED
                    && e.payload().path("quizId").asText().equals(presented.payload().path("quizId").asText()));
            if (completed) return ResponseEntity.ok(Map.of("quizAvailable", false));
            return ResponseEntity.ok(presentation(presented));
        }

        if (!quizPool.hasPool(gate)) {
            return ResponseEntity.ok(Map.of("quizAvailable", false,
                    "reason", "No quiz pool for gate " + gate));
        }

        // Select questions
        List<QuizQuestion> questions;
        if ("remedial".equals(req.trigger())) {
            // Get missing criteria from last judge result
            List<String> missingCriteria = getLastMissingCriteria(req.sessionId(), gate);
            questions = quizPool.selectForFailure(gate, missingCriteria, properties.quizRemedialCount());
        } else {
            questions = quizPool.selectForReinforcement(gate, properties.quizReinforcementCount());
        }

        if (questions.isEmpty()) {
            return ResponseEntity.ok(Map.of("quizAvailable", false));
        }

        String quizId = "quiz_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // QUIZ_PRESENTED event
        ObjectNode payload = mapper.createObjectNode();
        payload.put("gate", gate);
        payload.put("trigger", req.trigger());
        payload.put("quizId", quizId);
        payload.put("outcomeSeq", outcome.seq());
        ArrayNode questionIds = payload.putArray("questionIds");
        List<String> missingForPayload = "remedial".equals(req.trigger())
                ? getLastMissingCriteria(req.sessionId(), gate) : List.of();
        questions.forEach(q -> questionIds.add(q.id()));
        if (!missingForPayload.isEmpty()) {
            ArrayNode mc = payload.putArray("missingCriteria");
            missingForPayload.forEach(mc::add);
        }
        eventLog.append(req.sessionId(), session.getStudentId(), gate,
                EventType.QUIZ_PRESENTED, payload);

        // Build response without correct answers
        List<QuestionView> views = questions.stream().map(this::toView).toList();

        return ResponseEntity.ok(new QuizStartResponse(quizId, gate, req.trigger(), views));
    }

    // ── Submit quiz ─────────────────────────────────────────────────────

    record QuizSubmitRequest(String sessionId, String quizId,
                              List<AnswerEntry> answers) {}

    record AnswerEntry(String questionId, String answer) {}

    record QuizSubmitResponse(int score, int total, List<QuestionResult> results) {}

    record QuestionResult(String questionId, String stem, String studentAnswer,
                           boolean correct, Object correctAnswer, String explanation,
                           String textbookRef, String concept) {}

    @PostMapping("/api/gatekeeper/quiz/submit")
    @ResponseBody
    public synchronized ResponseEntity<?> submitQuiz(@RequestBody QuizSubmitRequest req, HttpServletRequest httpReq) {
        if (req == null || !validSessionId(req.sessionId()) || req.quizId() == null || req.answers() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Session ID, quiz ID and answers required"));
        }

        String clientIp = clientIp(httpReq);
        if (isRateLimited(clientIp, "quiz-submit", properties.submitRatePerMinute())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded"));
        }

        GatekeeperSession session = eventLog.loadSession(req.sessionId());
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        if (!properties.quizEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Quizzes are disabled"));
        }
        List<GatekeeperEvent> events = eventLog.readAll(req.sessionId());
        GatekeeperEvent presented = events.stream()
                .filter(e -> e.type() == EventType.QUIZ_PRESENTED
                        && req.quizId().equals(e.payload().path("quizId").asText()))
                .findFirst().orElse(null);
        if (presented == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Quiz not found for this session"));
        }
        GatekeeperEvent completed = events.stream()
                .filter(e -> e.type() == EventType.QUIZ_COMPLETED
                        && req.quizId().equals(e.payload().path("quizId").asText()))
                .findFirst().orElse(null);
        if (completed != null) {
            // A lost HTTP response can safely be retried without recording a second attempt.
            if (!completed.payload().has("result")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Quiz already completed"));
            }
            return ResponseEntity.ok(mapper.convertValue(completed.payload().get("result"), QuizSubmitResponse.class));
        }
        List<QuizQuestion> questions = questionsForPresentation(presented);
        Set<String> questionIds = new HashSet<>(questions.stream().map(QuizQuestion::id).toList());
        Map<String, String> submitted = new HashMap<>();
        for (AnswerEntry answer : req.answers()) {
            if (answer == null || answer.questionId() == null || answer.answer() == null
                    || answer.answer().length() > 4000 || !questionIds.contains(answer.questionId())
                    || submitted.putIfAbsent(answer.questionId(), answer.answer()) != null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid or duplicate quiz answer"));
            }
        }
        if (submitted.size() != questions.size()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Answer every quiz question"));
        }

        // Grade
        int score = 0;
        List<QuestionResult> results = new ArrayList<>();
        List<ObjectNode> answerNodes = new ArrayList<>();

        for (QuizQuestion q : questions) {
            String studentAnswer = submitted.get(q.id());

            boolean correct = gradeAnswer(q, studentAnswer);
            if (correct) score++;

            Object correctAnswer = "matching".equals(q.type())
                    ? q.pairs().stream().map(QuizQuestion.Pair::right).toList() : q.correct();
            results.add(new QuestionResult(q.id(), q.stem(), studentAnswer, correct, correctAnswer,
                    q.explanation(), q.textbookRef(), q.concept()));

            ObjectNode answerNode = mapper.createObjectNode();
            answerNode.put("questionId", q.id());
            answerNode.put("studentAnswer", studentAnswer);
            answerNode.put("correct", correct);
            answerNode.put("concept", q.concept());
            answerNode.put("textbookRef", q.textbookRef());
            answerNodes.add(answerNode);
        }

        int gate = presented.gate();

        // QUIZ_COMPLETED event
        ObjectNode payload = mapper.createObjectNode();
        payload.put("gate", gate);
        payload.put("quizId", req.quizId());
        payload.put("presentationSeq", presented.seq());
        payload.put("trigger", presented.payload().path("trigger").asText());
        ArrayNode answersArray = payload.putArray("answers");
        answerNodes.forEach(answersArray::add);
        payload.put("score", score);
        payload.put("total", questions.size());
        QuizSubmitResponse result = new QuizSubmitResponse(score, questions.size(), results);
        payload.set("result", mapper.valueToTree(result));
        eventLog.append(req.sessionId(), session.getStudentId(), gate,
                EventType.QUIZ_COMPLETED, payload);

        eventLog.saveSnapshot(req.sessionId(), eventLog.rebuildSnapshot(req.sessionId()));

        log.info("Quiz {} completed for session {}: {}/{}", req.quizId(), req.sessionId(),
                score, questions.size());

        return ResponseEntity.ok(result);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean gradeAnswer(QuizQuestion q, String studentAnswer) {
        if (studentAnswer == null || studentAnswer.isBlank()) return false;

        return switch (q.type()) {
            case "multiple_choice" -> studentAnswer.equalsIgnoreCase(String.valueOf(q.correct()));
            case "true_false" -> {
                boolean expected = q.correct() instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(q.correct()));
                yield studentAnswer.equalsIgnoreCase(String.valueOf(expected));
            }
            case "matching" -> {
                // JSON preserves matching labels containing commas.
                if (q.pairs() == null || q.pairs().isEmpty()) yield false;
                List<String> parts;
                try {
                    JsonNode node = mapper.readTree(studentAnswer);
                    if (!node.isArray()) yield false;
                    parts = new ArrayList<>();
                    for (JsonNode value : node) {
                        if (!value.isTextual()) yield false;
                        parts.add(value.asText());
                    }
                } catch (Exception e) {
                    yield false;
                }
                if (parts.size() != q.pairs().size()) yield false;
                boolean allCorrect = true;
                for (int i = 0; i < q.pairs().size(); i++) {
                    if (!parts.get(i).trim().equalsIgnoreCase(q.pairs().get(i).right().trim())) {
                        allCorrect = false;
                        break;
                    }
                }
                yield allCorrect;
            }
            default -> false;
        };
    }

    private QuestionView toView(QuizQuestion q) {
        List<PairView> pairViews = null;
        if ("matching".equals(q.type()) && q.pairs() != null && !q.pairs().isEmpty()) {
            // Shuffle the right-side options for display
            List<String> rightOptions = new ArrayList<>(q.pairs().stream()
                    .map(QuizQuestion.Pair::right).distinct().toList());
            java.util.Collections.shuffle(rightOptions);
            pairViews = q.pairs().stream()
                    .map(p -> new PairView(p.left(), rightOptions))
                    .toList();
        }
        return new QuestionView(q.id(), q.type(), q.stem(),
                "matching".equals(q.type()) ? null : q.options(),
                pairViews);
    }

    private List<String> getLastMissingCriteria(String sessionId, int gate) {
        List<GatekeeperEvent> events = eventLog.readAll(sessionId);
        for (int i = events.size() - 1; i >= 0; i--) {
            GatekeeperEvent event = events.get(i);
            if (event.type() == EventType.JUDGE_RESULT && event.gate() == gate && event.payload().has("missing")) {
                List<String> missing = new ArrayList<>();
                event.payload().get("missing").forEach(n -> missing.add(n.asText()));
                return missing;
            }
        }
        return List.of();
    }

    private boolean validSessionId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,100}");
    }

    private List<QuizQuestion> questionsForPresentation(GatekeeperEvent event) {
        List<QuizQuestion> questions = new ArrayList<>();
        for (JsonNode id : event.payload().path("questionIds")) {
            questions.add(quizPool.questionsForGate(event.gate()).stream()
                    .filter(q -> q.id().equals(id.asText())).findFirst().orElseThrow());
        }
        return questions;
    }

    private QuizStartResponse presentation(GatekeeperEvent event) {
        return new QuizStartResponse(event.payload().path("quizId").asText(), event.gate(),
                event.payload().path("trigger").asText(),
                questionsForPresentation(event).stream().map(this::toView).toList());
    }

    private String clientIp(HttpServletRequest request) {
        String xRealIp = request.getHeader("X-Real-IP");
        return (xRealIp != null && !xRealIp.isBlank()) ? xRealIp : request.getRemoteAddr();
    }

    private boolean isRateLimited(String clientIp, String action, int maxPerMinute) {
        long minuteBucket = System.currentTimeMillis() / 60_000;
        String key = clientIp + ":" + action + ":" + minuteBucket;
        AtomicInteger count = rateLimits.computeIfAbsent(key, k -> new AtomicInteger(0));
        int current = count.incrementAndGet();
        if (rateLimits.size() > 1000) {
            String prefix = minuteBucket + "";
            rateLimits.entrySet().removeIf(e -> !e.getKey().endsWith(":" + prefix));
        }
        return current > maxPerMinute;
    }
}
