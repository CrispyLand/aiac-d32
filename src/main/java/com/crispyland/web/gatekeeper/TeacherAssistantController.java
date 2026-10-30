package com.crispyland.web.gatekeeper;

import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.llm.CerebrasLlmClient;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.ToolCall;
import com.crispyland.agent.llm.ToolResult;
import com.crispyland.agent.llm.ToolRound;
import com.crispyland.agent.llm.ToolSpec;
import com.crispyland.agent.memory.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Teacher Assistant chat endpoint with its own tool loop.
 * Uses the Gatekeeper teacher MCP tool definitions, dispatching to
 * {@link TeacherToolDispatcher} directly (same JVM, no HTTP round-trip).
 * Max 8 tool rounds. Returns answer + tool trace.
 */
@RestController
@ConditionalOnProperty(name = "gatekeeper.enabled", havingValue = "true")
public class TeacherAssistantController {

    private static final Logger log = LoggerFactory.getLogger(TeacherAssistantController.class);
    private static final int MAX_TOOL_ROUNDS = 8;

    private final GatekeeperProperties properties;
    private final CerebrasLlmClient cerebras;
    private final TeacherToolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final String systemPrompt;
    private final List<ToolSpec> toolSpecs;

    public TeacherAssistantController(AgentProperties agentProperties,
                                       GatekeeperProperties gatekeeperProperties,
                                       GatekeeperEventLog eventLog) {
        this.properties = gatekeeperProperties;
        this.mapper = JsonMapper.builder().build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(agentProperties.readTimeout());
        RestClient rest = RestClient.builder().requestFactory(factory).build();
        this.cerebras = new CerebrasLlmClient(
                rest, mapper, agentProperties.endpoint(), agentProperties.apiKey());

        TeacherProgressAnalyzer analyzer = new TeacherProgressAnalyzer();
        this.dispatcher = new TeacherToolDispatcher(eventLog, analyzer, cerebras);

        this.systemPrompt = GateDefinition.loadResource("gatekeeper/teacher-assistant-system.txt");
        this.toolSpecs = buildToolSpecs();

        log.info("Teacher assistant enabled with {} tools", toolSpecs.size());
    }

    record AssistantRequest(String question, List<Map<String, String>> history) {}

    @PostMapping("/api/gatekeeper/teacher/assistant/chat")
    public ResponseEntity<?> chat(
            @RequestBody AssistantRequest req,
            @RequestHeader(value = "X-Teacher-Secret", required = false) String secret) {

        // Auth
        if (properties.teacherSecret() == null || properties.teacherSecret().isBlank()
                || !properties.teacherSecret().equals(secret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid teacher credentials"));
        }

        if (req.question() == null || req.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Question is required"));
        }

        try {
            // Build messages from history + new question
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(systemPrompt));
            if (req.history() != null) {
                for (Map<String, String> msg : req.history()) {
                    String role = msg.getOrDefault("role", "user");
                    String content = msg.getOrDefault("content", "");
                    if ("assistant".equals(role)) {
                        messages.add(Message.assistant(content));
                    } else {
                        messages.add(Message.user(content));
                    }
                }
            }
            messages.add(Message.user(req.question()));

            // Tool loop
            ChatRequest request = new ChatRequest(
                    "gpt-oss-120b", messages, 0.3, 4096, "", List.of(), null,
                    toolSpecs, List.of());

            ChatResponse response = cerebras.complete(request);
            List<ToolRound> rounds = new ArrayList<>();
            ArrayNode toolTrace = mapper.createArrayNode();

            while (response.wantsTools() && rounds.size() < MAX_TOOL_ROUNDS) {
                List<ToolResult> results = new ArrayList<>();
                for (ToolCall call : response.toolCalls()) {
                    log.info("Teacher assistant tool call: {} args={}", call.name(), call.arguments());
                    String result = dispatchTool(call);
                    results.add(ToolResult.of(call, result));

                    // Record in trace
                    ObjectNode traceEntry = mapper.createObjectNode();
                    traceEntry.put("tool", call.name());
                    traceEntry.set("args", mapper.valueToTree(call.arguments()));
                    traceEntry.put("result", truncate(result, 2000));
                    toolTrace.add(traceEntry);
                }
                rounds.add(new ToolRound(response.toolCalls(), results));
                response = cerebras.complete(request.withRounds(rounds));
            }

            ObjectNode out = mapper.createObjectNode();
            out.put("answer", response.content());
            out.set("toolTrace", toolTrace);
            out.put("toolRounds", rounds.size());
            return ResponseEntity.ok(out);

        } catch (Exception e) {
            log.error("Teacher assistant error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Assistant failed: " + e.getMessage()));
        }
    }

    // ── Tool dispatch ───────────────────────────────────────────────────

    private String dispatchTool(ToolCall call) {
        try {
            return switch (call.name()) {
                case "listSessions" -> dispatcher.listSessions(
                        str(call, "status"), intOrNull(call, "currentGate"),
                        intOrNull(call, "inactiveDays"), boolOrNull(call, "hasIntegritySignals"));
                case "getSessionOverview" -> dispatcher.getSessionOverview(str(call, "sessionId"));
                case "getTranscript" -> dispatcher.getTranscript(
                        str(call, "sessionId"), intOrNull(call, "gate"),
                        str(call, "eventTypes"), intOrNull(call, "limit"));
                case "getProgressAnalysis" -> dispatcher.getProgressAnalysis(str(call, "sessionId"));
                case "compareFinalSubmission" -> dispatcher.compareFinalSubmission(
                        str(call, "sessionId"), str(call, "finalText"));
                case "addTeacherNote" -> dispatcher.addTeacherNote(
                        str(call, "sessionId"), str(call, "note"),
                        Boolean.TRUE.equals(boolOrNull(call, "confirmed")));
                default -> "Unknown tool: " + call.name();
            };
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", call.name(), e.getMessage());
            return "Tool error: " + e.getMessage();
        }
    }

    private static String str(ToolCall call, String key) {
        Object v = call.arguments().get(key);
        return v != null ? v.toString() : null;
    }

    private static Integer intOrNull(ToolCall call, String key) {
        Object v = call.arguments().get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean boolOrNull(ToolCall call, String key) {
        Object v = call.arguments().get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    // ── Tool definitions ────────────────────────────────────────────────

    static List<ToolSpec> buildToolSpecs() {
        return List.of(
                new ToolSpec("listSessions",
                        "List Gatekeeper sessions with optional filters. Returns session summaries, not full transcripts.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "status", Map.of("type", "string", "description",
                                                "Filter by status: 'in_progress' or 'complete'"),
                                        "currentGate", Map.of("type", "integer", "description",
                                                "Filter by current gate number (0-6)"),
                                        "inactiveDays", Map.of("type", "integer", "description",
                                                "Only sessions inactive for at least this many days"),
                                        "hasIntegritySignals", Map.of("type", "boolean", "description",
                                                "Only sessions with integrity signals")),
                                "required", List.of(),
                                "additionalProperties", false)),

                new ToolSpec("getSessionOverview",
                        "Get detailed overview of a single Gatekeeper session: gate progress, event counts, integrity stats, last analysis status.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "sessionId", Map.of("type", "string", "description",
                                                "The session ID to look up")),
                                "required", List.of("sessionId"),
                                "additionalProperties", false)),

                new ToolSpec("getTranscript",
                        "Get event log entries for a session. Filter by gate, event types, and limit. "
                                + "Event types: STUDENT_MESSAGE, TUTOR_MESSAGE, GATE_SUBMITTED, JUDGE_RESULT, "
                                + "GATE_UNLOCKED, GATE_FAILED, STUDENT_INTEGRITY_SIGNAL, PASTE_ATTEMPT, "
                                + "TEACHER_DECISION. Max 50 events per call.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "sessionId", Map.of("type", "string", "description",
                                                "The session ID"),
                                        "gate", Map.of("type", "integer", "description",
                                                "Filter to events from this gate only"),
                                        "eventTypes", Map.of("type", "string", "description",
                                                "Comma-separated event type names to filter by"),
                                        "limit", Map.of("type", "integer", "description",
                                                "Max events to return (default 50, max 50)")),
                                "required", List.of("sessionId"),
                                "additionalProperties", false)),

                new ToolSpec("getProgressAnalysis",
                        "Get or generate an AI progress analysis for a session. Uses cached analysis if recent, "
                                + "otherwise generates a fresh one. Returns attention status, knowledge gaps, "
                                + "misconceptions, and suggestions.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "sessionId", Map.of("type", "string", "description",
                                                "The session ID to analyze")),
                                "required", List.of("sessionId"),
                                "additionalProperties", false)),

                new ToolSpec("compareFinalSubmission",
                        "Compare a student's final submission text against their Gatekeeper session work. "
                                + "Reports: matching ideas, new ideas, major changes, polish differences. "
                                + "Cites evidence with gate numbers, timestamps, and student quotes. "
                                + "Does NOT accuse students — reports factual observations only.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "sessionId", Map.of("type", "string", "description",
                                                "The session ID to compare against"),
                                        "finalText", Map.of("type", "string", "description",
                                                "The final submission text to compare")),
                                "required", List.of("sessionId", "finalText"),
                                "additionalProperties", false)),

                new ToolSpec("addTeacherNote",
                        "Add a teacher note to a session. Stored as a permanent teacher-only event. "
                                + "First call with confirmed=false to get confirmation prompt, "
                                + "then call again with confirmed=true to store.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "sessionId", Map.of("type", "string", "description",
                                                "The session ID to add the note to"),
                                        "note", Map.of("type", "string", "description",
                                                "The teacher note text"),
                                        "confirmed", Map.of("type", "boolean", "description",
                                                "Set to true to confirm and store the note")),
                                "required", List.of("sessionId", "note"),
                                "additionalProperties", false))
        );
    }
}
