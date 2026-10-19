package com.crispyland.web.gatekeeper;

import com.crispyland.agent.llm.CerebrasLlmClient;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.memory.Message;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generates teacher-only progress analysis and indicative grades.
 * Low temperature, structured JSON output, evidence-cited claims.
 * Never shown to students. Never included in student exports.
 */
public class TeacherProgressAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TeacherProgressAnalyzer.class);

    private final String analyzerSystem;
    private final String analyzerSchema;
    private final String gradeSystem;
    private final String gradeSchema;
    private final ObjectMapper mapper;

    public TeacherProgressAnalyzer() {
        this.analyzerSystem = GateDefinition.loadResource("gatekeeper/teacher-analyzer-system.txt");
        this.analyzerSchema = GateDefinition.loadResource("gatekeeper/teacher-analyzer-schema.json");
        this.gradeSystem = GateDefinition.loadResource("gatekeeper/teacher-grade-system.txt");
        this.gradeSchema = GateDefinition.loadResource("gatekeeper/teacher-grade-schema.json");
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Analyze a student's session. Returns structured JSON with progress summary,
     * demonstrated understanding, knowledge gaps, misconceptions, suggestions,
     * integrity notes, and attention status.
     */
    public JsonNode analyze(List<GatekeeperEvent> events, GatekeeperSession session,
                             String ragContext, CerebrasLlmClient cerebras) {
        try {
            String eventSummary = formatEventsForAnalysis(events);
            StringBuilder userMsg = new StringBuilder();
            userMsg.append("Session: ").append(session.getSessionId()).append("\n");
            userMsg.append("Current gate: ").append(session.getCurrentGate()).append("\n");
            userMsg.append("Passed gates: ").append(session.getPassedGates()).append("\n");
            userMsg.append("Business summary: ").append(
                    session.getBusinessSummary().isEmpty() ? "(none)" : session.getBusinessSummary()).append("\n\n");
            userMsg.append("Event log:\n\n").append(eventSummary);
            if (ragContext != null && !ragContext.isBlank()) {
                userMsg.append("\n\nReference material context:\n").append(ragContext);
            }

            ChatRequest request = new ChatRequest(
                    "gpt-oss-120b",
                    List.of(Message.system(analyzerSystem), Message.user(userMsg.toString())),
                    0.1, 4096, "", List.of(), analyzerSchema);
            ChatResponse response = cerebras.complete(request);
            return mapper.readTree(response.content());
        } catch (Exception e) {
            log.warn("Teacher analysis failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generate an indicative grade for a completed session.
     * Returns null if session is incomplete or performance standards are placeholders.
     */
    public JsonNode generateGrade(List<GatekeeperEvent> events, GatekeeperSession session,
                                   String performanceStandards, CerebrasLlmClient cerebras) {
        try {
            String eventSummary = formatEventsForAnalysis(events);
            StringBuilder userMsg = new StringBuilder();
            userMsg.append("Student business summary:\n").append(session.getBusinessSummary()).append("\n\n");
            userMsg.append("Performance standards:\n").append(performanceStandards).append("\n\n");
            userMsg.append("Full event log:\n\n").append(eventSummary);

            ChatRequest request = new ChatRequest(
                    "gpt-oss-120b",
                    List.of(Message.system(gradeSystem), Message.user(userMsg.toString())),
                    0.1, 4096, "", List.of(), gradeSchema);
            ChatResponse response = cerebras.complete(request);
            return mapper.readTree(response.content());
        } catch (Exception e) {
            log.warn("Grade generation failed: {}", e.getMessage());
            return null;
        }
    }

    private String formatEventsForAnalysis(List<GatekeeperEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (GatekeeperEvent event : events) {
            sb.append("[seq=").append(event.seq())
              .append(" id=").append(event.eventId())
              .append(" ").append(event.timestamp())
              .append(" gate=").append(event.gate())
              .append(" type=").append(event.type().name());
            if (event.payload() != null) {
                try {
                    sb.append("] ").append(mapper.writeValueAsString(event.payload()));
                } catch (Exception e) {
                    sb.append("] (payload error)");
                }
            } else {
                sb.append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
