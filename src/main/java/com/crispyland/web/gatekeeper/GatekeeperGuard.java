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
 * Output guard: checks every tutor response for assessable content about the student's business.
 * Fail-closed: if the guard errors, times out, or returns invalid JSON,
 * the tutor reply is NOT shown — regenerate once, then return a safe fallback.
 */
public class GatekeeperGuard {

    private static final Logger log = LoggerFactory.getLogger(GatekeeperGuard.class);
    private static final String SAFE_FALLBACK = "Can you tell me more about what you're thinking?";

    private final String systemPrompt;
    private final String responseSchema;
    private final ObjectMapper mapper;

    public record GuardResult(boolean containsAssessableContent, String reason, String action) {
        static GuardResult pass(String reason) {
            return new GuardResult(false, reason, "pass");
        }
        static GuardResult flagged(String reason) {
            return new GuardResult(true, reason, "regenerate");
        }
        static GuardResult error(String reason) {
            return new GuardResult(true, reason, "error");
        }
        static GuardResult fallback(String reason) {
            return new GuardResult(true, reason, "fallback");
        }
    }

    public GatekeeperGuard() {
        this.systemPrompt = GateDefinition.loadResource("gatekeeper/guard-system.txt");
        this.responseSchema = GateDefinition.loadResource("gatekeeper/guard-response-schema.json");
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Check a tutor response. Returns the guard result.
     */
    public GuardResult check(String tutorResponse, String businessSummary,
                              String gateBlock, CerebrasLlmClient cerebras) {
        String userMessage = "Tutor response to check:\n" + tutorResponse
                + "\n\nStudent's business summary:\n"
                + (businessSummary.isEmpty() ? "(none yet)" : businessSummary)
                + "\n\nCurrent gate:\n" + gateBlock;

        try {
            ChatRequest request = new ChatRequest(
                    "gpt-oss-120b",
                    List.of(Message.system(systemPrompt), Message.user(userMessage)),
                    0.0, 512, "", List.of(), responseSchema);
            ChatResponse response = cerebras.complete(request);
            String content = response.content();

            JsonNode json = mapper.readTree(content);
            boolean flagged = json.get("contains_assessable_content").asBoolean();
            String reason = json.get("reason").asText("");

            return flagged ? GuardResult.flagged(reason) : GuardResult.pass(reason);
        } catch (Exception e) {
            log.warn("Guard check failed: {}", e.getMessage());
            return GuardResult.error("Guard error: " + e.getMessage());
        }
    }

    public static String safeFallback() {
        return SAFE_FALLBACK;
    }
}
