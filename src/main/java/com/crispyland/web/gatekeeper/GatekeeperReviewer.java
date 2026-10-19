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
 * Reviewer: summarises a session for the teacher at export time.
 * Low temperature, structured JSON output.
 * Never assigns a grade. Teacher export only — never shown to students.
 */
public class GatekeeperReviewer {

    private static final Logger log = LoggerFactory.getLogger(GatekeeperReviewer.class);

    private final String systemPrompt;
    private final String responseSchema;
    private final ObjectMapper mapper;

    public GatekeeperReviewer() {
        this.systemPrompt = GateDefinition.loadResource("gatekeeper/reviewer-system.txt");
        this.responseSchema = GateDefinition.loadResource("gatekeeper/reviewer-response-schema.json");
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Review a full session event log. Returns the reviewer's structured JSON output,
     * or null if the review fails.
     */
    public JsonNode review(List<GatekeeperEvent> events, CerebrasLlmClient cerebras) {
        try {
            StringBuilder eventSummary = new StringBuilder();
            for (GatekeeperEvent event : events) {
                eventSummary.append("[").append(event.timestamp()).append("] ")
                        .append(event.type().name());
                if (event.payload() != null) {
                    eventSummary.append(": ").append(mapper.writeValueAsString(event.payload()));
                }
                eventSummary.append("\n");
            }

            ChatRequest request = new ChatRequest(
                    "gpt-oss-120b",
                    List.of(Message.system(systemPrompt),
                            Message.user("Session event log:\n\n" + eventSummary)),
                    0.1, 1024, "", List.of(), responseSchema);
            ChatResponse response = cerebras.complete(request);
            return mapper.readTree(response.content());
        } catch (Exception e) {
            log.warn("Reviewer failed: {}", e.getMessage());
            return null;
        }
    }
}
