package com.crispyland.web.gatekeeper;

import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.llm.CerebrasLlmClient;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.memory.Message;
import com.crispyland.rag.RagConfig;
import com.crispyland.rag.RagProperties;
import com.crispyland.rag.RagSearchResult;
import com.crispyland.rag.RagSearcher;
import com.crispyland.rag.ScoredChunk;
import com.crispyland.web.gatekeeper.GatekeeperEvent.EventType;
import com.crispyland.web.gatekeeper.GatekeeperGuard.GuardResult;
import com.crispyland.web.gatekeeper.GatekeeperSession.TimestampedMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Controller
@ConditionalOnProperty(name = "gatekeeper.enabled", havingValue = "true")
public class GatekeeperController {

    private static final Logger log = LoggerFactory.getLogger(GatekeeperController.class);

    private final GatekeeperProperties properties;
    private final CerebrasLlmClient cerebras;
    private final ObjectMapper mapper;
    private final GatekeeperEventLog eventLog;
    private final List<GateDefinition> gates;
    private final String tutorSystemTemplate;
    private final String resumePromptTemplate;
    private final GatekeeperGuard guard;
    private final IntegrityClassifier integrityClassifier;
    private final GatekeeperReviewer reviewer;
    private final RagSearcher ragSearcher;
    private final RagProperties ragProperties;
    private final ExecutorService asyncExecutor;
    private final ConcurrentHashMap<String, AtomicInteger> rateLimits = new ConcurrentHashMap<>();

    // Gate names for resume prompt
    private static final String[] GATE_NAMES = {
            "The idea", "Ownership and legal requirements", "Name and location",
            "Product", "Influences", "Risks and opportunities", "New technology"
    };

    public GatekeeperController(AgentProperties agentProperties,
                                 GatekeeperProperties gatekeeperProperties,
                                 RagSearcher ragSearcher,
                                 RagProperties ragProperties) {
        this.properties = gatekeeperProperties;
        this.mapper = JsonMapper.builder().build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(agentProperties.readTimeout());
        RestClient rest = RestClient.builder().requestFactory(factory).build();
        this.cerebras = new CerebrasLlmClient(
                rest, mapper, agentProperties.endpoint(), agentProperties.apiKey());

        Path dataDir = Paths.get(gatekeeperProperties.dataDir());
        this.eventLog = new GatekeeperEventLog(dataDir);
        this.gates = GateDefinition.loadAll();
        this.tutorSystemTemplate = GateDefinition.loadTutorSystem();
        this.resumePromptTemplate = GateDefinition.loadResumePrompt();
        this.guard = new GatekeeperGuard();
        this.integrityClassifier = new IntegrityClassifier();
        this.reviewer = new GatekeeperReviewer();
        this.ragSearcher = ragSearcher;
        this.ragProperties = ragProperties;
        this.asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gatekeeper-integrity-classifier");
            t.setDaemon(true);
            return t;
        });

        log.info("Gatekeeper enabled with {} gates, locale={}", gates.size(), properties.locale());
    }

    // ── Page ─────────────────────────────────────────────────────────────

    @GetMapping("/gatekeeper")
    public String page() {
        return "gatekeeper";
    }

    // ── Start session ────────────────────────────────────────────────────

    record StartRequest(String studentId, String accessCode) {}
    record StartResponse(String sessionId, String openingMessage, int currentGate, int totalGates) {}

    @PostMapping("/api/gatekeeper/start")
    @ResponseBody
    public ResponseEntity<?> startSession(@RequestBody StartRequest req,
                                           HttpServletRequest httpReq) {
        if (req.studentId() == null || req.studentId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Student ID is required"));
        }

        // Access code check (mandatory on VPS when configured)
        if (!properties.accessCode().isBlank()
                && !properties.accessCode().equals(req.accessCode())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid access code"));
        }

        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // SESSION_STARTED event
        ObjectNode payload = mapper.createObjectNode();
        payload.put("studentId", req.studentId());
        payload.put("locale", properties.locale());
        payload.put("accessCodeVerified", !properties.accessCode().isBlank());
        eventLog.append(sessionId, req.studentId(), 0, EventType.SESSION_STARTED, payload);

        // Scripted Gate 0 opening — no LLM call
        String opening = gates.isEmpty() ? "Welcome!" : gates.get(0).openingMessage();
        ObjectNode tutorPayload = mapper.createObjectNode();
        tutorPayload.put("content", opening);
        tutorPayload.put("source", "scripted");
        tutorPayload.put("promptTokens", 0);
        tutorPayload.put("completionTokens", 0);
        eventLog.append(sessionId, req.studentId(), 0, EventType.TUTOR_MESSAGE, tutorPayload);

        // Save snapshot
        GatekeeperSession session = eventLog.rebuildSnapshot(sessionId);
        eventLog.saveSnapshot(sessionId, session);

        return ResponseEntity.ok(new StartResponse(
                sessionId, opening, 0, gates.size()));
    }

    // ── Resume session ───────────────────────────────────────────────────

    record ResumeRequest(String sessionId, String accessCode) {}
    record ResumeResponse(String sessionId, String studentId, int currentGate,
                           List<Integer> passedGates, List<TimestampedMessage> conversation,
                           String resumeMessage, int totalGates) {}

    @PostMapping("/api/gatekeeper/resume")
    @ResponseBody
    public ResponseEntity<?> resumeSession(@RequestBody ResumeRequest req,
                                            HttpServletRequest httpReq) {
        if (req.sessionId() == null || req.sessionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Session ID is required"));
        }

        // Access code required for resume
        if (!properties.accessCode().isBlank()
                && !properties.accessCode().equals(req.accessCode())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid access code"));
        }

        if (!eventLog.sessionExists(req.sessionId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        GatekeeperSession session = eventLog.loadSession(req.sessionId());
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        // SESSION_RESUMED event
        ObjectNode payload = mapper.createObjectNode();
        payload.put("reason", "Student returned");
        eventLog.append(req.sessionId(), session.getStudentId(),
                session.getCurrentGate(), EventType.SESSION_RESUMED, payload);

        // Scripted resume prompt
        String gateName = session.getCurrentGate() < GATE_NAMES.length
                ? GATE_NAMES[session.getCurrentGate()] : "Gate " + session.getCurrentGate();
        String resumeMsg = resumePromptTemplate
                .replace("{{gate}}", String.valueOf(session.getCurrentGate()))
                .replace("{{gate_name}}", gateName);

        ObjectNode tutorPayload = mapper.createObjectNode();
        tutorPayload.put("content", resumeMsg);
        tutorPayload.put("source", "scripted");
        tutorPayload.put("promptTokens", 0);
        tutorPayload.put("completionTokens", 0);
        eventLog.append(req.sessionId(), session.getStudentId(),
                session.getCurrentGate(), EventType.TUTOR_MESSAGE, tutorPayload);

        // Reload session after appending
        session = eventLog.loadSession(req.sessionId());
        eventLog.saveSnapshot(req.sessionId(), session);

        return ResponseEntity.ok(new ResumeResponse(
                req.sessionId(), session.getStudentId(), session.getCurrentGate(),
                session.getPassedGates(), session.currentConversation(),
                resumeMsg, gates.size()));
    }

    // ── Chat ─────────────────────────────────────────────────────────────

    record ChatRequest_(String sessionId, String message) {}
    record ChatResponse_(String response, int currentGate, String source) {}

    @PostMapping("/api/gatekeeper/chat")
    @ResponseBody
    public ResponseEntity<?> chat(@RequestBody ChatRequest_ req, HttpServletRequest httpReq) {
        if (req.sessionId() == null || req.message() == null || req.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Session ID and message required"));
        }

        String clientIp = clientIp(httpReq);
        if (isRateLimited(clientIp, "chat", properties.chatRatePerMinute())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded. Please wait a moment."));
        }

        GatekeeperSession session = eventLog.loadSession(req.sessionId());
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        int gate = session.getCurrentGate();

        // STUDENT_MESSAGE event
        ObjectNode studentPayload = mapper.createObjectNode();
        studentPayload.put("content", req.message());
        GatekeeperEvent studentEvent = eventLog.append(
                req.sessionId(), session.getStudentId(), gate,
                EventType.STUDENT_MESSAGE, studentPayload);

        // Reload session to include the new student message
        session = eventLog.rebuildSnapshot(req.sessionId());

        // Build tutor system prompt with placeholders filled
        String systemPrompt = buildTutorPrompt(session, gate, req.message());
        List<Message> messages = buildMessageList(systemPrompt, session, gate);

        // Call tutor LLM
        String tutorResponse;
        long promptTokens = 0, completionTokens = 0;
        try {
            ChatRequest llmRequest = new ChatRequest(
                    "gpt-oss-120b", messages, 0.7, 300, "", List.of(), "");
            ChatResponse llmResponse = cerebras.complete(llmRequest);
            tutorResponse = llmResponse.content();
            if (llmResponse.usage() != null) {
                promptTokens = llmResponse.usage().promptTokens();
                completionTokens = llmResponse.usage().completionTokens();
            }
        } catch (Exception e) {
            log.error("Tutor LLM call failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Tutor is temporarily unavailable"));
        }

        // Output guard (fail-closed)
        GateDefinition currentGate = gate < gates.size() ? gates.get(gate) : null;
        String gateBlock = currentGate != null ? currentGate.tutorBlock() : "";
        GuardResult guardResult = guard.check(tutorResponse, session.getBusinessSummary(),
                gateBlock, cerebras);

        // Record guard decision
        ObjectNode guardPayload = mapper.createObjectNode();
        guardPayload.put("passed", !guardResult.containsAssessableContent());
        guardPayload.put("reason", guardResult.reason());
        guardPayload.put("action", guardResult.action());
        eventLog.append(req.sessionId(), session.getStudentId(), gate,
                EventType.GUARD_DECISION, guardPayload);

        // If guard flagged or errored, regenerate once
        if (guardResult.containsAssessableContent()) {
            log.warn("Guard flagged tutor response for session {}: {}", req.sessionId(), guardResult.reason());
            try {
                // Regenerate with additional instruction
                List<Message> retryMessages = new ArrayList<>(messages);
                retryMessages.add(Message.assistant(tutorResponse));
                retryMessages.add(Message.system(
                        "Your previous response contained assessable content about the student's business. "
                        + "Respond again: ask a question instead of providing analysis or answers."));
                ChatRequest retryRequest = new ChatRequest(
                        "gpt-oss-120b", retryMessages, 0.7, 300, "", List.of(), "");
                ChatResponse retryResponse = cerebras.complete(retryRequest);
                tutorResponse = retryResponse.content();

                // Re-check with guard
                GuardResult retryGuard = guard.check(tutorResponse, session.getBusinessSummary(),
                        gateBlock, cerebras);
                ObjectNode retryGuardPayload = mapper.createObjectNode();
                retryGuardPayload.put("passed", !retryGuard.containsAssessableContent());
                retryGuardPayload.put("reason", retryGuard.reason());
                retryGuardPayload.put("action", "regenerate_recheck");
                eventLog.append(req.sessionId(), session.getStudentId(), gate,
                        EventType.GUARD_DECISION, retryGuardPayload);

                if (retryGuard.containsAssessableContent()) {
                    // Still unsafe — use safe fallback
                    tutorResponse = GatekeeperGuard.safeFallback();
                    ObjectNode fallbackPayload = mapper.createObjectNode();
                    fallbackPayload.put("passed", false);
                    fallbackPayload.put("reason", "Fallback after failed regeneration");
                    fallbackPayload.put("action", "fallback");
                    eventLog.append(req.sessionId(), session.getStudentId(), gate,
                            EventType.GUARD_DECISION, fallbackPayload);
                }
            } catch (Exception e) {
                log.error("Guard regeneration failed: {}", e.getMessage());
                tutorResponse = GatekeeperGuard.safeFallback();
            }
        }

        // TUTOR_MESSAGE event
        long finalPromptTokens = promptTokens;
        long finalCompletionTokens = completionTokens;
        ObjectNode tutorPayload = mapper.createObjectNode();
        tutorPayload.put("content", tutorResponse);
        tutorPayload.put("source", "llm");
        tutorPayload.put("promptTokens", finalPromptTokens);
        tutorPayload.put("completionTokens", finalCompletionTokens);
        eventLog.append(req.sessionId(), session.getStudentId(), gate,
                EventType.TUTOR_MESSAGE, tutorPayload);

        // Save snapshot
        session = eventLog.rebuildSnapshot(req.sessionId());
        eventLog.saveSnapshot(req.sessionId(), session);

        // Async integrity classification
        String studentEventId = studentEvent.eventId();
        String studentMessage = req.message();
        String sid = req.sessionId();
        String studentId = session.getStudentId();
        int gateNum = gate;
        asyncExecutor.submit(() -> {
            try {
                JsonNode signalPayload = integrityClassifier.classify(
                        studentMessage, studentEventId, cerebras);
                if (signalPayload != null) {
                    eventLog.append(sid, studentId, gateNum,
                            EventType.STUDENT_INTEGRITY_SIGNAL, signalPayload);
                }
            } catch (Exception e) {
                log.warn("Async integrity classification failed: {}", e.getMessage());
            }
        });

        return ResponseEntity.ok(new ChatResponse_(tutorResponse, gate, "llm"));
    }

    // ── Submit for gate ──────────────────────────────────────────────────

    record SubmitRequest(String sessionId, String answer) {}
    record SubmitResponse(boolean passed, int currentGate, String message,
                           String openingMessage) {}

    @PostMapping("/api/gatekeeper/submit")
    @ResponseBody
    public ResponseEntity<?> submit(@RequestBody SubmitRequest req, HttpServletRequest httpReq) {
        if (req.sessionId() == null || req.answer() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Session ID and answer required"));
        }

        String clientIp = clientIp(httpReq);
        if (isRateLimited(clientIp, "submit", properties.submitRatePerMinute())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded. Please wait."));
        }

        GatekeeperSession session = eventLog.loadSession(req.sessionId());
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        int gate = session.getCurrentGate();
        if (gate >= gates.size()) {
            return ResponseEntity.badRequest().body(Map.of("error", "All gates completed"));
        }

        GateDefinition gateDef = gates.get(gate);
        int wordCount = GatekeeperSession.wordCount(req.answer());

        // Word count check
        if (wordCount > gateDef.maxWords()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Answer is " + wordCount + " words, maximum is " + gateDef.maxWords()));
        }
        if (wordCount == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Answer cannot be empty"));
        }

        // GATE_SUBMITTED event
        ObjectNode submitPayload = mapper.createObjectNode();
        submitPayload.put("answer", req.answer());
        submitPayload.put("wordCount", wordCount);
        submitPayload.put("gate", gate);
        eventLog.append(req.sessionId(), session.getStudentId(), gate,
                EventType.GATE_SUBMITTED, submitPayload);

        // Build judge prompt
        String judgeSystem = gateDef.judgeSystem();
        String judgeUser = gateDef.judgeUserTemplate()
                .replace("{{conversation_with_roles_and_timestamps}}",
                        session.formatConversation(gate))
                .replace("{{submitted_answer}}", req.answer())
                .replace("{{word_count}}", String.valueOf(wordCount))
                .replace("{{business_summary}}", session.getBusinessSummary())
                .replace("{{locale}}", properties.locale());

        // Call judge LLM
        JudgeResult judgeResult;
        try {
            ChatRequest judgeRequest = new ChatRequest(
                    "gpt-oss-120b",
                    List.of(Message.system(judgeSystem), Message.user(judgeUser)),
                    0.0, 4096, "", List.of(), gateDef.responseSchema());
            ChatResponse judgeResponse = cerebras.complete(judgeRequest);
            judgeResult = mapper.readValue(judgeResponse.content(), JudgeResult.class);
        } catch (Exception e) {
            log.error("Judge LLM call failed for session {}: {}", req.sessionId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Judge is temporarily unavailable"));
        }

        // JUDGE_RESULT event
        ObjectNode judgePayload = mapper.valueToTree(judgeResult);
        eventLog.append(req.sessionId(), session.getStudentId(), gate,
                EventType.JUDGE_RESULT, judgePayload);

        // Backend validation — do NOT trust judge.met alone
        boolean allCriteriaMet = judgeResult.criteria().values().stream()
                .allMatch(JudgeResult.CriterionResult::met);
        boolean wordCountOk = wordCount <= gateDef.maxWords();

        // Evidence verification
        List<String> earlierStudentMessages = session.studentMessages(gate);
        List<EvidenceVerifier.VerifyResult> evidenceIssues =
                EvidenceVerifier.verifyAll(judgeResult, req.answer(), earlierStudentMessages);
        boolean hasRejectedEvidence = evidenceIssues.stream()
                .anyMatch(r -> r.level() == EvidenceVerifier.MatchLevel.REJECTED);
        List<String> fuzzyMatches = evidenceIssues.stream()
                .filter(r -> r.level() == EvidenceVerifier.MatchLevel.FUZZY)
                .map(r -> r.criterionKey())
                .toList();

        log.info("Gate {} validation for {}: allCriteriaMet={}, wordCountOk={} ({}/{}), " +
                "hasRejectedEvidence={}, evidenceIssues={}, judgeResult.met={}, criteria={}",
                gate, req.sessionId(), allCriteriaMet, wordCountOk, wordCount, gateDef.maxWords(),
                hasRejectedEvidence, evidenceIssues, judgeResult.met(),
                judgeResult.criteria());

        boolean passed = allCriteriaMet && wordCountOk && !hasRejectedEvidence;

        if (passed) {
            // GATE_UNLOCKED event
            ObjectNode unlockPayload = mapper.createObjectNode();
            unlockPayload.put("gate", gate);
            unlockPayload.put("businessSummaryEntry", req.answer());
            if (!fuzzyMatches.isEmpty()) {
                unlockPayload.put("fuzzyEvidenceMatches", String.join(", ", fuzzyMatches));
            }
            eventLog.append(req.sessionId(), session.getStudentId(), gate,
                    EventType.GATE_UNLOCKED, unlockPayload);

            // Scripted next-gate opening
            String openingMessage = "";
            int nextGate = gate + 1;
            if (nextGate < gates.size()) {
                openingMessage = gates.get(nextGate).openingMessage();
                ObjectNode nextTutorPayload = mapper.createObjectNode();
                nextTutorPayload.put("content", openingMessage);
                nextTutorPayload.put("source", "scripted");
                nextTutorPayload.put("promptTokens", 0);
                nextTutorPayload.put("completionTokens", 0);
                eventLog.append(req.sessionId(), session.getStudentId(), nextGate,
                        EventType.TUTOR_MESSAGE, nextTutorPayload);
            }

            session = eventLog.rebuildSnapshot(req.sessionId());
            eventLog.saveSnapshot(req.sessionId(), session);

            return ResponseEntity.ok(new SubmitResponse(true, session.getCurrentGate(),
                    "Gate " + gate + " unlocked!", openingMessage));
        } else {
            // GATE_FAILED event
            ObjectNode failPayload = mapper.createObjectNode();
            failPayload.put("gate", gate);
            StringBuilder reason = new StringBuilder();
            if (!allCriteriaMet) reason.append("Not all criteria met. ");
            if (!wordCountOk) reason.append("Word count exceeded. ");
            if (hasRejectedEvidence) reason.append("Evidence not found in student text. ");
            failPayload.put("reason", reason.toString().trim());
            ObjectNode details = failPayload.putObject("backendValidationDetails");
            details.put("allCriteriaMet", allCriteriaMet);
            details.put("wordCountOk", wordCountOk);
            details.put("hasRejectedEvidence", hasRejectedEvidence);
            eventLog.append(req.sessionId(), session.getStudentId(), gate,
                    EventType.GATE_FAILED, failPayload);

            // Update session with judge feedback for tutor
            session = eventLog.rebuildSnapshot(req.sessionId());
            eventLog.saveSnapshot(req.sessionId(), session);

            return ResponseEntity.ok(new SubmitResponse(false, gate,
                    "Not yet — keep working with the tutor.", ""));
        }
    }

    // ── Paste log ────────────────────────────────────────────────────────

    record PasteLogRequest(String sessionId, int gate, String content) {}

    @PostMapping("/api/gatekeeper/paste-log")
    @ResponseBody
    public ResponseEntity<?> pasteLog(@RequestBody PasteLogRequest req, HttpServletRequest httpReq) {
        if (req.sessionId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Session ID required"));
        }

        String clientIp = clientIp(httpReq);
        if (isRateLimited(clientIp, "paste", properties.pasteRatePerMinute())) {
            return ResponseEntity.ok(Map.of("logged", false));
        }

        GatekeeperSession session = eventLog.loadSession(req.sessionId());
        if (session == null) {
            return ResponseEntity.ok(Map.of("logged", false));
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("gate", req.gate());
        payload.put("pasteLength", req.content() != null ? req.content().length() : 0);

        // SHA-256 hash of pasted content
        if (req.content() != null) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(req.content().getBytes(StandardCharsets.UTF_8));
                payload.put("contentHash", HexFormat.of().formatHex(hash));
            } catch (Exception e) {
                // ignore
            }
        }

        // Short excerpt only if explicitly enabled
        if (properties.logPasteExcerpt() && req.content() != null) {
            payload.put("excerpt", req.content().substring(0,
                    Math.min(100, req.content().length())));
        }

        eventLog.append(req.sessionId(), session.getStudentId(),
                req.gate(), EventType.PASTE_ATTEMPT, payload);

        return ResponseEntity.ok(Map.of("logged", true));
    }

    // ── Student export ───────────────────────────────────────────────────

    record ExportRequest(String sessionId) {}

    @PostMapping("/api/gatekeeper/export")
    @ResponseBody
    public ResponseEntity<?> studentExport(@RequestBody ExportRequest req) {
        if (req.sessionId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Session ID required"));
        }

        // Verify hash chain
        String chainError = eventLog.verifyChain(req.sessionId());
        if (chainError != null) {
            log.error("Hash chain verification failed for {}: {}", req.sessionId(), chainError);
        }

        List<GatekeeperEvent> events = eventLog.readAll(req.sessionId());
        GatekeeperSession session = GatekeeperSession.fromEvents(events);

        // Filter to student-visible events only
        List<GatekeeperEvent> studentEvents = events.stream()
                .filter(e -> isStudentVisible(e.type()))
                .toList();

        ObjectNode export = mapper.createObjectNode();
        export.put("sessionId", req.sessionId());
        export.put("studentId", session.getStudentId());
        export.put("currentGate", session.getCurrentGate());
        export.set("passedGates", mapper.valueToTree(session.getPassedGates()));
        export.put("exportedAt", Instant.now().toString());
        export.put("hashChainValid", chainError == null);
        export.put("finalHash", session.getLastHash());
        export.set("events", mapper.valueToTree(studentEvents));

        // TRANSCRIPT_EXPORTED event
        ObjectNode exportEventPayload = mapper.createObjectNode();
        exportEventPayload.put("exportType", "student");
        exportEventPayload.put("hash", session.getLastHash());
        eventLog.append(req.sessionId(), session.getStudentId(),
                session.getCurrentGate(), EventType.TRANSCRIPT_EXPORTED, exportEventPayload);

        return ResponseEntity.ok(export);
    }

    // ── Teacher export ───────────────────────────────────────────────────

    @PostMapping("/api/gatekeeper/teacher-export")
    @ResponseBody
    public ResponseEntity<?> teacherExport(@RequestBody ExportRequest req,
                                            @RequestHeader(value = "X-Teacher-Secret", required = false) String secret) {
        // Teacher secret validation — in header, never logged
        if (properties.teacherSecret() == null || properties.teacherSecret().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Teacher export not configured"));
        }
        if (!properties.teacherSecret().equals(secret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid teacher secret"));
        }
        if (req.sessionId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Session ID required"));
        }

        // Verify hash chain
        String chainError = eventLog.verifyChain(req.sessionId());

        List<GatekeeperEvent> events = eventLog.readAll(req.sessionId());
        GatekeeperSession session = GatekeeperSession.fromEvents(events);

        ObjectNode export = mapper.createObjectNode();
        export.put("sessionId", req.sessionId());
        export.put("studentId", session.getStudentId());
        export.put("currentGate", session.getCurrentGate());
        export.set("passedGates", mapper.valueToTree(session.getPassedGates()));
        export.put("businessSummary", session.getBusinessSummary());
        export.put("exportedAt", Instant.now().toString());
        export.put("hashChainValid", chainError == null);
        if (chainError != null) {
            export.put("hashChainError", chainError);
        }
        export.put("finalHash", session.getLastHash());
        export.set("events", mapper.valueToTree(events)); // Full event log

        // Reviewer summary — teacher export only
        JsonNode reviewerResult = reviewer.review(events, cerebras);
        if (reviewerResult != null) {
            export.set("reviewerSummary", reviewerResult);
        }

        // TRANSCRIPT_EXPORTED event
        ObjectNode exportEventPayload = mapper.createObjectNode();
        exportEventPayload.put("exportType", "teacher");
        exportEventPayload.put("hash", session.getLastHash());
        eventLog.append(req.sessionId(), session.getStudentId(),
                session.getCurrentGate(), EventType.TRANSCRIPT_EXPORTED, exportEventPayload);

        return ResponseEntity.ok(export);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String buildTutorPrompt(GatekeeperSession session, int gate, String studentMessage) {
        GateDefinition gateDef = gate < gates.size() ? gates.get(gate) : null;
        String gateBlock = gateDef != null
                ? gateDef.tutorBlock().replace("{{locale}}", properties.locale())
                : "";
        String judgeFeedback = session.getLastJudgeNextFocus().isBlank()
                ? "None."
                : session.getLastJudgeNextFocus();

        // RAG search for relevant textbook content
        String ragContext = fetchRagContext(studentMessage, gate);

        return tutorSystemTemplate
                .replace("{{business_summary}}",
                        session.getBusinessSummary().isEmpty()
                                ? "(none yet)" : session.getBusinessSummary())
                .replace("{{gate_block}}", gateBlock)
                .replace("{{judge_feedback_or_none}}", judgeFeedback)
                .replace("{{rag_context}}", ragContext);
    }

    private String fetchRagContext(String query, int gate) {
        try {
            RagConfig config = new RagConfig(10, 3, 0.3f, false, 0, false);
            RagSearchResult result = ragSearcher.search(query, List.of(), config, "gatekeeper");
            if (result.finalChunks().isEmpty()) {
                return "No reference material found for this topic.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Relevant textbook/course material (you may cite short quotes with page numbers):\n\n");
            for (int i = 0; i < result.finalChunks().size(); i++) {
                ScoredChunk sc = result.finalChunks().get(i);
                sb.append("[").append(i + 1).append("] (").append(sc.chunk().sourceFile()).append(")\n");
                sb.append(sc.chunk().text()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("RAG search failed for gatekeeper: {}", e.getMessage());
            return "No reference material available for this gate.";
        }
    }

    private List<Message> buildMessageList(String systemPrompt, GatekeeperSession session, int gate) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));

        for (TimestampedMessage msg : session.currentConversation()) {
            switch (msg.role()) {
                case "STUDENT" -> messages.add(Message.user(msg.content()));
                case "TUTOR" -> messages.add(Message.assistant(msg.content()));
            }
        }
        return messages;
    }

    private boolean isStudentVisible(EventType type) {
        return switch (type) {
            case SESSION_STARTED, SESSION_RESUMED, STUDENT_MESSAGE, TUTOR_MESSAGE,
                 GATE_SUBMITTED, GATE_UNLOCKED, GATE_FAILED, PASTE_ATTEMPT,
                 TRANSCRIPT_EXPORTED -> true;
            case JUDGE_RESULT, GUARD_DECISION, STUDENT_INTEGRITY_SIGNAL,
                 TEACHER_ANALYSIS_GENERATED, TEACHER_DECISION -> false;
        };
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

        // Cleanup old buckets periodically
        if (rateLimits.size() > 1000) {
            String prefix = minuteBucket + "";
            rateLimits.entrySet().removeIf(e -> !e.getKey().endsWith(":" + prefix));
        }

        return current > maxPerMinute;
    }
}
