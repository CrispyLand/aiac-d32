package com.crispyland.web.gatekeeper;

import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.llm.CerebrasLlmClient;
import com.crispyland.rag.RagSearcher;
import com.crispyland.rag.RagProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Internal teacher API — called by the MCP server over localhost.
 * Protected by bearer token + localhost-only validation.
 * Never exposed through nginx.
 */
@RestController
@RequestMapping("/api/gatekeeper/internal/teacher")
@ConditionalOnProperty(name = "gatekeeper.enabled", havingValue = "true")
public class TeacherInternalApiController {

    private static final Logger log = LoggerFactory.getLogger(TeacherInternalApiController.class);

    private final GatekeeperProperties properties;
    private final TeacherToolDispatcher dispatcher;

    public TeacherInternalApiController(AgentProperties agentProperties,
                                         GatekeeperProperties gatekeeperProperties,
                                         GatekeeperEventLog eventLog) {
        this.properties = gatekeeperProperties;

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(agentProperties.readTimeout());
        RestClient rest = RestClient.builder().requestFactory(factory).build();
        ObjectMapper mapper = JsonMapper.builder().build();
        CerebrasLlmClient cerebras = new CerebrasLlmClient(
                rest, mapper, agentProperties.endpoint(), agentProperties.apiKey());

        TeacherProgressAnalyzer analyzer = new TeacherProgressAnalyzer();

        this.dispatcher = new TeacherToolDispatcher(eventLog, analyzer, cerebras);
        log.info("Teacher internal API enabled");
    }

    // ── Auth ────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, String>> validateRequest(String authHeader, HttpServletRequest request) {
        // Localhost check
        String remoteAddr = request.getRemoteAddr();
        if (!"127.0.0.1".equals(remoteAddr) && !"0:0:0:0:0:0:0:1".equals(remoteAddr)
                && !"::1".equals(remoteAddr)) {
            log.warn("Internal API called from non-localhost: {}", remoteAddr);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Internal API is localhost-only"));
        }

        // Bearer token check
        String expectedToken = properties.internalToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Internal token not configured"));
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")
                || !expectedToken.equals(authHeader.substring(7))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid bearer token"));
        }
        return null; // valid
    }

    // ── Endpoints ───────────────────────────────────────────────────────

    @GetMapping("/sessions")
    public ResponseEntity<?> listSessions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer currentGate,
            @RequestParam(required = false) Integer inactiveDays,
            @RequestParam(required = false) Boolean hasIntegritySignals,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        ResponseEntity<Map<String, String>> err = validateRequest(authHeader, request);
        if (err != null) return err;

        String result = dispatcher.listSessions(status, currentGate, inactiveDays, hasIntegritySignals);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @GetMapping("/session/{sessionId}/overview")
    public ResponseEntity<?> getSessionOverview(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        ResponseEntity<Map<String, String>> err = validateRequest(authHeader, request);
        if (err != null) return err;

        String result = dispatcher.getSessionOverview(sessionId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @GetMapping("/session/{sessionId}/transcript")
    public ResponseEntity<?> getTranscript(
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer gate,
            @RequestParam(required = false) String eventTypes,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        ResponseEntity<Map<String, String>> err = validateRequest(authHeader, request);
        if (err != null) return err;

        String result = dispatcher.getTranscript(sessionId, gate, eventTypes, limit);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @GetMapping("/session/{sessionId}/analysis")
    public ResponseEntity<?> getProgressAnalysis(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        ResponseEntity<Map<String, String>> err = validateRequest(authHeader, request);
        if (err != null) return err;

        String result = dispatcher.getProgressAnalysis(sessionId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    record CompareRequest(String finalText) {}

    @PostMapping("/session/{sessionId}/compare")
    public ResponseEntity<?> compareFinalSubmission(
            @PathVariable String sessionId,
            @RequestBody CompareRequest body,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        ResponseEntity<Map<String, String>> err = validateRequest(authHeader, request);
        if (err != null) return err;

        String result = dispatcher.compareFinalSubmission(sessionId, body.finalText());
        return ResponseEntity.ok(Map.of("result", result));
    }

    record NoteRequest(String note, boolean confirmed) {}

    @PostMapping("/session/{sessionId}/note")
    public ResponseEntity<?> addTeacherNote(
            @PathVariable String sessionId,
            @RequestBody NoteRequest body,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        ResponseEntity<Map<String, String>> err = validateRequest(authHeader, request);
        if (err != null) return err;

        String result = dispatcher.addTeacherNote(sessionId, body.note(), body.confirmed());
        return ResponseEntity.ok(Map.of("result", result));
    }
}
