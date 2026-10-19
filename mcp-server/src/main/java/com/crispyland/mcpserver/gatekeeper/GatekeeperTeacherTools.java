package com.crispyland.mcpserver.gatekeeper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Gatekeeper teacher MCP tools. Read-only queries plus addTeacherNote.
 * Calls the Gatekeeper app's internal teacher API over localhost with a bearer token.
 * Never exposed publicly — the MCP server itself listens on localhost:8081.
 */
@Service
@ConditionalOnProperty(name = "gatekeeper.enabled", havingValue = "true")
public class GatekeeperTeacherTools {

    private static final Logger log = LoggerFactory.getLogger(GatekeeperTeacherTools.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final String bearerToken;

    public GatekeeperTeacherTools(GatekeeperTeacherProperties properties) {
        this.baseUrl = properties.internalUrl() + "/api/gatekeeper/internal/teacher";
        this.bearerToken = properties.internalToken();
        this.restClient = RestClient.create();
        log.info("Gatekeeper teacher tools enabled, internal API at {}", baseUrl);
    }

    @McpTool(name = "listSessions",
            description = "List Gatekeeper student sessions with optional filters. "
                    + "Returns session summaries: student code, gate progress, status, failures, "
                    + "integrity signals. Does not return full transcripts.")
    public String listSessions(
            @McpToolParam(description = "Filter by status: 'in_progress' or 'complete'",
                    required = false) String status,
            @McpToolParam(description = "Filter by current gate number (0-6)",
                    required = false) Integer currentGate,
            @McpToolParam(description = "Only sessions inactive for at least this many days",
                    required = false) Integer inactiveDays,
            @McpToolParam(description = "Only sessions with integrity signals",
                    required = false) Boolean hasIntegritySignals) {
        try {
            StringBuilder query = new StringBuilder();
            if (status != null) append(query, "status", status);
            if (currentGate != null) append(query, "currentGate", currentGate.toString());
            if (inactiveDays != null) append(query, "inactiveDays", inactiveDays.toString());
            if (hasIntegritySignals != null) append(query, "hasIntegritySignals", hasIntegritySignals.toString());

            return callGet("/sessions" + query);
        } catch (Exception e) {
            return "Error listing sessions: " + e.getMessage();
        }
    }

    @McpTool(name = "getSessionOverview",
            description = "Get detailed overview of a single Gatekeeper session: gate progress, "
                    + "event counts, per-gate stats, integrity signals, last analysis status, "
                    + "hash chain validity.")
    public String getSessionOverview(
            @McpToolParam(description = "The session ID to look up", required = true) String sessionId) {
        try {
            return callGet("/session/" + encode(sessionId) + "/overview");
        } catch (Exception e) {
            return "Error getting session overview: " + e.getMessage();
        }
    }

    @McpTool(name = "getTranscript",
            description = "Get event log entries for a Gatekeeper session. Filter by gate, "
                    + "event types, and limit. Event types: STUDENT_MESSAGE, TUTOR_MESSAGE, "
                    + "GATE_SUBMITTED, JUDGE_RESULT, GATE_UNLOCKED, GATE_FAILED, "
                    + "STUDENT_INTEGRITY_SIGNAL, PASTE_ATTEMPT, TEACHER_DECISION. Max 50 per call.")
    public String getTranscript(
            @McpToolParam(description = "The session ID", required = true) String sessionId,
            @McpToolParam(description = "Filter to events from this gate only",
                    required = false) Integer gate,
            @McpToolParam(description = "Comma-separated event type names to filter by",
                    required = false) String eventTypes,
            @McpToolParam(description = "Max events to return (default 50, max 50)",
                    required = false) Integer limit) {
        try {
            StringBuilder query = new StringBuilder();
            if (gate != null) append(query, "gate", gate.toString());
            if (eventTypes != null) append(query, "eventTypes", eventTypes);
            if (limit != null) append(query, "limit", limit.toString());

            return callGet("/session/" + encode(sessionId) + "/transcript" + query);
        } catch (Exception e) {
            return "Error getting transcript: " + e.getMessage();
        }
    }

    @McpTool(name = "getProgressAnalysis",
            description = "Get or generate an AI progress analysis for a Gatekeeper session. "
                    + "Uses cached analysis if recent, otherwise generates a fresh one. "
                    + "Returns: attention status, demonstrated understanding, knowledge gaps, "
                    + "misconceptions, suggestions, integrity notes.")
    public String getProgressAnalysis(
            @McpToolParam(description = "The session ID to analyze", required = true) String sessionId) {
        try {
            return callGet("/session/" + encode(sessionId) + "/analysis");
        } catch (Exception e) {
            return "Error getting analysis: " + e.getMessage();
        }
    }

    @McpTool(name = "compareFinalSubmission",
            description = "Compare a student's final submission text against their Gatekeeper "
                    + "session work. Reports: ideas matching earlier reasoning, new ideas not in "
                    + "the session, major changes in business decisions, sections more polished "
                    + "than earlier writing. Cites evidence. Does NOT accuse students.")
    public String compareFinalSubmission(
            @McpToolParam(description = "The session ID to compare against", required = true) String sessionId,
            @McpToolParam(description = "The final submission text to compare", required = true) String finalText) {
        try {
            String body = "{\"finalText\":" + jsonString(finalText) + "}";
            return callPost("/session/" + encode(sessionId) + "/compare", body);
        } catch (Exception e) {
            return "Error comparing submission: " + e.getMessage();
        }
    }

    @McpTool(name = "addTeacherNote",
            description = "Add a teacher note to a Gatekeeper session. Stored as a permanent "
                    + "teacher-only event. Set confirmed=false first to see what will be stored, "
                    + "then confirmed=true to commit.")
    public String addTeacherNote(
            @McpToolParam(description = "The session ID", required = true) String sessionId,
            @McpToolParam(description = "The note text", required = true) String note,
            @McpToolParam(description = "Set true to confirm and store", required = false) Boolean confirmed) {
        try {
            String body = "{\"note\":" + jsonString(note) + ",\"confirmed\":"
                    + (Boolean.TRUE.equals(confirmed)) + "}";
            return callPost("/session/" + encode(sessionId) + "/note", body);
        } catch (Exception e) {
            return "Error adding note: " + e.getMessage();
        }
    }

    // ── HTTP helpers ────────────────────────────────────────────────────

    private String callGet(String path) {
        String responseBody = restClient.get()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .body(String.class);
        return extractResult(responseBody);
    }

    private String callPost(String path, String jsonBody) {
        String responseBody = restClient.post()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(String.class);
        return extractResult(responseBody);
    }

    private String extractResult(String responseBody) {
        if (responseBody == null) return "(empty response)";
        // The internal API wraps results in {"result": "..."} — extract the value
        int idx = responseBody.indexOf("\"result\":");
        if (idx >= 0) {
            // Simple extraction: find the value string
            int valueStart = responseBody.indexOf('"', idx + 9);
            if (valueStart >= 0) {
                // Parse the JSON string value properly
                StringBuilder sb = new StringBuilder();
                int i = valueStart + 1;
                while (i < responseBody.length()) {
                    char c = responseBody.charAt(i);
                    if (c == '\\' && i + 1 < responseBody.length()) {
                        char next = responseBody.charAt(i + 1);
                        if (next == '"') { sb.append('"'); i += 2; continue; }
                        if (next == 'n') { sb.append('\n'); i += 2; continue; }
                        if (next == '\\') { sb.append('\\'); i += 2; continue; }
                        if (next == 't') { sb.append('\t'); i += 2; continue; }
                        sb.append(c); i++; continue;
                    }
                    if (c == '"') break;
                    sb.append(c);
                    i++;
                }
                return sb.toString();
            }
        }
        return responseBody;
    }

    private static void append(StringBuilder query, String key, String value) {
        query.append(query.isEmpty() ? '?' : '&')
             .append(key).append('=').append(encode(value));
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Minimal JSON string escaping for building request bodies. */
    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }
}
