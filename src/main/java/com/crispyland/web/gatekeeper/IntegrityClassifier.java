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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Classifies student messages for integrity signals.
 * Runs async (after tutor response is returned) to avoid adding latency.
 * Records STUDENT_INTEGRITY_SIGNAL events, never blocks.
 */
public class IntegrityClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntegrityClassifier.class);

    private final String systemPrompt;
    private final String responseSchema;
    private final ObjectMapper mapper;

    public IntegrityClassifier() {
        this.systemPrompt = GateDefinition.loadResource("gatekeeper/integrity-classifier-system.txt");
        this.responseSchema = GateDefinition.loadResource("gatekeeper/integrity-classifier-schema.json");
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Classify a student message. Returns the payload for a STUDENT_INTEGRITY_SIGNAL event,
     * or null if no signal detected (all "none").
     */
    public JsonNode classify(String studentMessage, String triggeringEventId,
                              CerebrasLlmClient cerebras) {
        try {
            ChatRequest request = new ChatRequest(
                    "gpt-oss-120b",
                    List.of(Message.system(systemPrompt), Message.user(studentMessage)),
                    0.0, 256, "", List.of(), responseSchema);
            ChatResponse response = cerebras.complete(request);
            String content = response.content();

            JsonNode json = mapper.readTree(content);
            JsonNode signals = json.get("signals");

            if (signals == null || !signals.isArray() || signals.isEmpty()) {
                return null;
            }

            // Check if all signals are "none"
            boolean hasRealSignal = false;
            for (JsonNode signal : signals) {
                String signalType = signal.get("signal_type").asText("none");
                if (!"none".equals(signalType)) {
                    hasRealSignal = true;
                    break;
                }
            }

            if (!hasRealSignal) {
                return null;
            }

            // Build the event payload
            ObjectNode payload = mapper.createObjectNode();
            payload.put("triggeringEventId", triggeringEventId);
            ArrayNode signalsArray = payload.putArray("signals");
            for (JsonNode signal : signals) {
                String signalType = signal.get("signal_type").asText("none");
                if (!"none".equals(signalType)) {
                    ObjectNode s = signalsArray.addObject();
                    s.put("signalType", signalType);
                    s.put("confidence", signal.get("confidence").asDouble(0.0));
                }
            }
            return payload;
        } catch (Exception e) {
            log.warn("Integrity classification failed: {}", e.getMessage());
            return null;
        }
    }
}
