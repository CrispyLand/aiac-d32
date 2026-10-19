package com.crispyland.web.gatekeeper;

import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.llm.CerebrasLlmClient;
import com.crispyland.agent.memory.Message;
import com.crispyland.rag.RagConfig;
import com.crispyland.rag.RagProperties;
import com.crispyland.rag.RagSearchResult;
import com.crispyland.rag.RagSearcher;
import com.crispyland.rag.ScoredChunk;
import com.crispyland.web.gatekeeper.GatekeeperEvent.EventType;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Controller
@ConditionalOnProperty(name = "gatekeeper.enabled", havingValue = "true")
public class TeacherDashboardController {

    private static final Logger log = LoggerFactory.getLogger(TeacherDashboardController.class);

    private final GatekeeperProperties properties;
    private final CerebrasLlmClient cerebras;
    private final ObjectMapper mapper;
    private final GatekeeperEventLog eventLog;
    private final TeacherProgressAnalyzer analyzer;
    private final RagSearcher ragSearcher;
    private final List<GateDefinition> gates;

    public TeacherDashboardController(AgentProperties agentProperties,
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
        this.analyzer = new TeacherProgressAnalyzer();
        this.ragSearcher = ragSearcher;
        this.gates = GateDefinition.loadAll();

        log.info("Teacher dashboard enabled");
    }

    // ── Auth helper ─────────────────────────────────────────────────────

    private ResponseEntity<?> validateTeacher(String secret) {
        if (properties.teacherSecret() == null || properties.teacherSecret().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Teacher access not configured"));
        }
        if (!properties.teacherSecret().equals(secret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid teacher credentials"));
        }
        return null; // valid
    }

    // ── Page ────────────────────────────────────────────────────────────

    @GetMapping("/gatekeeper/teacher")
    public String teacherPage() {
        return "gatekeeper-teacher";
    }

    // ── Class overview ──────────────────────────────────────────────────

    @GetMapping("/api/gatekeeper/teacher/sessions")
    @ResponseBody
    public ResponseEntity<?> listSessions(
            @RequestHeader(value = "X-Teacher-Secret", required = false) String secret) {
        ResponseEntity<?> authErr = validateTeacher(secret);
        if (authErr != null) return authErr;

        List<String> sessionIds = eventLog.listSessionIds();
        ArrayNode sessions = mapper.createArrayNode();

        for (String sid : sessionIds) {
            List<GatekeeperEvent> events = eventLog.readAll(sid);
            GatekeeperSession session = GatekeeperSession.fromEvents(events);

            ObjectNode row = mapper.createObjectNode();
            row.put("sessionId", sid);
            row.put("studentId", session.getStudentId() != null ? session.getStudentId() : "unknown");
            row.put("currentGate", session.getCurrentGate());
            row.put("passedGatesCount", session.getPassedGates().size());
            row.put("totalGates", gates.size());
            row.put("status", session.getCurrentGate() >= gates.size() ? "complete" : "in_progress");
            row.put("eventCount", session.getEventCount());

            // Count specific event types
            long failedSubmissions = events.stream().filter(e -> e.type() == EventType.GATE_FAILED).count();
            long integritySignals = events.stream().filter(e -> e.type() == EventType.STUDENT_INTEGRITY_SIGNAL).count();
            long pasteAttempts = events.stream().filter(e -> e.type() == EventType.PASTE_ATTEMPT).count();
            row.put("failedSubmissions", failedSubmissions);
            row.put("integritySignals", integritySignals);
            row.put("pasteAttempts", pasteAttempts);

            // Last activity
            if (session.getLastActivityAt() != null) {
                row.put("lastActivity", session.getLastActivityAt().toString());
            }

            // Check for existing analysis
            boolean hasAnalysis = events.stream()
                    .anyMatch(e -> e.type() == EventType.TEACHER_ANALYSIS_GENERATED);
            row.put("hasAnalysis", hasAnalysis);
            if (hasAnalysis) {
                GatekeeperEvent lastAnalysis = events.stream()
                        .filter(e -> e.type() == EventType.TEACHER_ANALYSIS_GENERATED)
                        .reduce((a, b) -> b).orElse(null);
                if (lastAnalysis != null && lastAnalysis.payload() != null
                        && lastAnalysis.payload().has("attentionStatus")) {
                    row.put("attentionStatus", lastAnalysis.payload().get("attentionStatus").asText(""));
                }
            }

            sessions.add(row);
        }

        return ResponseEntity.ok(sessions);
    }

    // ── Session detail ──────────────────────────────────────────────────

    @GetMapping("/api/gatekeeper/teacher/session/{sessionId}")
    @ResponseBody
    public ResponseEntity<?> sessionDetail(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Teacher-Secret", required = false) String secret) {
        ResponseEntity<?> authErr = validateTeacher(secret);
        if (authErr != null) return authErr;

        if (!eventLog.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        List<GatekeeperEvent> events = eventLog.readAll(sessionId);
        GatekeeperSession session = GatekeeperSession.fromEvents(events);

        // Hash chain verification
        String chainError = eventLog.verifyChain(sessionId);

        ObjectNode detail = mapper.createObjectNode();
        detail.put("sessionId", sessionId);
        detail.put("studentId", session.getStudentId());
        detail.put("currentGate", session.getCurrentGate());
        detail.set("passedGates", mapper.valueToTree(session.getPassedGates()));
        detail.put("totalGates", gates.size());
        detail.put("status", session.getCurrentGate() >= gates.size() ? "complete" : "in_progress");
        detail.put("businessSummary", session.getBusinessSummary());
        detail.put("hashChainValid", chainError == null);
        if (chainError != null) {
            detail.put("hashChainError", chainError);
        }
        if (session.getCreatedAt() != null) {
            detail.put("createdAt", session.getCreatedAt().toString());
        }
        if (session.getLastActivityAt() != null) {
            detail.put("lastActivity", session.getLastActivityAt().toString());
        }

        // Per-gate stats
        ArrayNode gateStats = detail.putArray("gateStats");
        for (int g = 0; g < gates.size(); g++) {
            final int gate = g;
            ObjectNode gs = gateStats.addObject();
            gs.put("gate", gate);
            gs.put("passed", session.getPassedGates().contains(gate));
            gs.put("attempts", events.stream()
                    .filter(e -> e.type() == EventType.GATE_SUBMITTED && e.gate() == gate).count());
            gs.put("failures", events.stream()
                    .filter(e -> e.type() == EventType.GATE_FAILED && e.gate() == gate).count());
        }

        // Full event log
        detail.set("events", mapper.valueToTree(events));

        // Latest analysis if exists
        GatekeeperEvent lastAnalysis = events.stream()
                .filter(e -> e.type() == EventType.TEACHER_ANALYSIS_GENERATED)
                .reduce((a, b) -> b).orElse(null);
        if (lastAnalysis != null && lastAnalysis.payload() != null) {
            detail.set("analysis", lastAnalysis.payload());
            int analysisSeq = lastAnalysis.payload().has("lastSeqCovered")
                    ? lastAnalysis.payload().get("lastSeqCovered").asInt(0) : lastAnalysis.seq();
            detail.put("analysisCoveredSeq", analysisSeq);
            detail.put("eventsSinceAnalysis", session.getEventCount() - analysisSeq);
        }

        // Latest teacher decision if exists
        GatekeeperEvent lastDecision = events.stream()
                .filter(e -> e.type() == EventType.TEACHER_DECISION)
                .reduce((a, b) -> b).orElse(null);
        if (lastDecision != null && lastDecision.payload() != null) {
            detail.set("teacherDecision", lastDecision.payload());
        }

        // Indicative grade availability
        boolean allGatesComplete = session.getCurrentGate() >= gates.size();
        boolean performanceStandardsAvailable = !isPlaceholder();
        detail.put("gradeAvailable", allGatesComplete && performanceStandardsAvailable);
        if (!allGatesComplete) {
            detail.put("gradeUnavailableReason", "Not available until all gates are completed.");
        } else if (!performanceStandardsAvailable) {
            detail.put("gradeUnavailableReason",
                    "Performance standards are placeholders. Replace docs/gatekeeper-rag/performance-standards.md with official SACE standards to enable indicative grading.");
        }

        return ResponseEntity.ok(detail);
    }

    // ── Generate / refresh analysis ─────────────────────────────────────

    @PostMapping("/api/gatekeeper/teacher/analyze/{sessionId}")
    @ResponseBody
    public ResponseEntity<?> analyzeSession(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Teacher-Secret", required = false) String secret) {
        ResponseEntity<?> authErr = validateTeacher(secret);
        if (authErr != null) return authErr;

        if (!eventLog.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        List<GatekeeperEvent> events = eventLog.readAll(sessionId);
        GatekeeperSession session = GatekeeperSession.fromEvents(events);

        // Fetch RAG context for analysis
        String ragContext = fetchRagContext(session);

        // Run analysis
        JsonNode analysis = analyzer.analyze(events, session, ragContext, cerebras);
        if (analysis == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Analysis generation failed"));
        }

        // Store as event with caching metadata
        ObjectNode payload = mapper.createObjectNode();
        payload.set("analysis", analysis);
        payload.put("lastSeqCovered", session.getEventCount());
        payload.put("generatedAt", Instant.now().toString());
        if (analysis.has("attentionStatus")) {
            payload.put("attentionStatus", analysis.get("attentionStatus").asText(""));
        }

        eventLog.append(sessionId, session.getStudentId(),
                session.getCurrentGate(), EventType.TEACHER_ANALYSIS_GENERATED, payload);

        ObjectNode response = mapper.createObjectNode();
        response.set("analysis", analysis);
        response.put("lastSeqCovered", session.getEventCount());
        return ResponseEntity.ok(response);
    }

    // ── Generate indicative grade ───────────────────────────────────────

    @PostMapping("/api/gatekeeper/teacher/grade/{sessionId}")
    @ResponseBody
    public ResponseEntity<?> generateGrade(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Teacher-Secret", required = false) String secret) {
        ResponseEntity<?> authErr = validateTeacher(secret);
        if (authErr != null) return authErr;

        if (!eventLog.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        List<GatekeeperEvent> events = eventLog.readAll(sessionId);
        GatekeeperSession session = GatekeeperSession.fromEvents(events);

        if (session.getCurrentGate() < gates.size()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Not available until all gates are completed."));
        }

        if (isPlaceholder()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Performance standards are placeholders. Replace docs/gatekeeper-rag/performance-standards.md with official SACE standards to enable indicative grading."));
        }

        String standards = GateDefinition.loadResource("docs/gatekeeper-rag/performance-standards.md");
        if (standards == null) standards = "";

        JsonNode grade = analyzer.generateGrade(events, session, standards, cerebras);
        if (grade == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Grade generation failed"));
        }

        return ResponseEntity.ok(grade);
    }

    // ── Teacher decision ────────────────────────────────────────────────

    record TeacherDecisionRequest(String decision, String overrideGrade, String note) {}

    @PostMapping("/api/gatekeeper/teacher/decision/{sessionId}")
    @ResponseBody
    public ResponseEntity<?> recordDecision(
            @PathVariable String sessionId,
            @RequestBody TeacherDecisionRequest req,
            @RequestHeader(value = "X-Teacher-Secret", required = false) String secret) {
        ResponseEntity<?> authErr = validateTeacher(secret);
        if (authErr != null) return authErr;

        if (!eventLog.sessionExists(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        GatekeeperSession session = eventLog.loadSession(sessionId);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found"));
        }

        // Validate decision
        if (req.decision() == null || req.decision().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Decision is required"));
        }
        if (!List.of("accept", "override", "note").contains(req.decision())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Decision must be: accept, override, or note"));
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("decision", req.decision());
        if (req.overrideGrade() != null && !req.overrideGrade().isBlank()) {
            payload.put("overrideGrade", req.overrideGrade());
        }
        if (req.note() != null && !req.note().isBlank()) {
            payload.put("note", req.note());
        }
        payload.put("recordedAt", Instant.now().toString());

        eventLog.append(sessionId, session.getStudentId(),
                session.getCurrentGate(), EventType.TEACHER_DECISION, payload);

        return ResponseEntity.ok(Map.of("recorded", true));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean isPlaceholder() {
        String content = GateDefinition.loadResource("gatekeeper/performance-standards-check.txt");
        // Fall back to checking the actual RAG doc
        try {
            java.io.InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("docs/gatekeeper-rag/performance-standards.md");
            if (is == null) return true;
            String text = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            is.close();
            return text.contains("[PLACEHOLDER");
        } catch (Exception e) {
            return true;
        }
    }

    private String fetchRagContext(GatekeeperSession session) {
        try {
            String query = "business innovation assessment " + session.getBusinessSummary();
            RagConfig config = new RagConfig(10, 3, 0.3f, false, 0, false);
            RagSearchResult result = ragSearcher.search(query, List.of(), config, "gatekeeper");
            if (result.finalChunks().isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (ScoredChunk sc : result.finalChunks()) {
                sb.append(sc.chunk().text()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
