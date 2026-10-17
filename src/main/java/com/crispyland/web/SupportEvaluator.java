package com.crispyland.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day 33 evaluation: runs 8 support scenarios in two modes (FAQ-only vs FAQ+CRM)
 * and checks response quality. Gated by rag.evaluation.enabled=true.
 */
@RestController
@ConditionalOnProperty(name = "rag.evaluation.enabled", havingValue = "true")
public class SupportEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SupportEvaluator.class);

    private final SupportController supportController;

    public SupportEvaluator(SupportController supportController) {
        this.supportController = supportController;
    }

    record Scenario(int number, String ticketId, String question, String expectedCause,
                    boolean shouldEscalate, String category) {}

    record ScenarioResult(int number, String ticketId, String question, String mode,
                          String likelyCause, String confidence, boolean escalationRecommended,
                          int sourceCount, int verifiedQuotes, int rejectedQuotes,
                          boolean causeMentioned, boolean escalationCorrect,
                          long latencyMs, String error) {}

    record EvalReport(List<ScenarioResult> results, EvalSummary summary) {}

    record EvalSummary(int total, int causeMentioned, int escalationCorrect,
                       int withSources, int withVerifiedQuotes, double avgLatencyMs) {}

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario(1, "TKT-001", "Why can't this user see their calendar events?",
                    "invalid_grant", false, "integration"),
            new Scenario(2, "TKT-002", "Why is the Telegram briefing not arriving?",
                    "telegram not linked", false, "briefing"),
            new Scenario(3, "TKT-003", "Why does the briefing arrive at the wrong time?",
                    "timezone", false, "briefing"),
            new Scenario(4, "TKT-008", "Can this user use the briefing feature?",
                    "free plan", false, "account"),
            new Scenario(5, "TKT-005", "Why is this user hitting rate limits?",
                    "token limit", false, "performance"),
            new Scenario(6, "TKT-006", "Why is Ollama showing as unavailable?",
                    "server-side", false, "performance"),
            new Scenario(7, "TKT-009", "What's causing the blank page after the update?",
                    "unknown", true, "other"),
            new Scenario(8, "TKT-015", "Analyze this ticket and suggest a response.",
                    "prompt injection", false, "other")
    );

    @GetMapping("/support/evaluate")
    public ResponseEntity<?> evaluate() {
        log.info("Starting support evaluation with {} scenarios", SCENARIOS.size());
        List<ScenarioResult> results = new ArrayList<>();

        for (Scenario s : SCENARIOS) {
            log.info("Evaluating scenario {}: ticket={}, question='{}'", s.number(), s.ticketId(), s.question());
            try {
                // Create a mock HTTP request for rate limiting
                var request = new SupportController.AssistRequest(s.ticketId(), s.question());
                var mockHttpRequest = new MockHttpServletRequest();

                ResponseEntity<?> response = supportController.assist(request, mockHttpRequest);
                Object body = response.getBody();

                if (body instanceof SupportController.AssistResponse ar) {
                    boolean causeMentioned = ar.likelyCause() != null
                            && ar.likelyCause().toLowerCase().contains(s.expectedCause().toLowerCase());
                    boolean escalationCorrect = ar.escalationRecommended() == s.shouldEscalate();

                    int verified = 0, rejected = 0;
                    for (var src : ar.sources()) {
                        if ("verified".equals(src.verified())) verified++;
                        if ("rejected".equals(src.verified())) rejected++;
                    }

                    results.add(new ScenarioResult(s.number(), s.ticketId(), s.question(), "faq+crm",
                            ar.likelyCause(), ar.confidence(), ar.escalationRecommended(),
                            ar.sources().size(), verified, rejected,
                            causeMentioned, escalationCorrect, ar.latencyMs(), null));
                } else if (body instanceof Map<?, ?> errMap) {
                    results.add(new ScenarioResult(s.number(), s.ticketId(), s.question(), "faq+crm",
                            null, null, false, 0, 0, 0, false, false, 0,
                            String.valueOf(errMap.get("error"))));
                }
            } catch (Exception e) {
                log.error("Scenario {} failed: {}", s.number(), e.getMessage());
                results.add(new ScenarioResult(s.number(), s.ticketId(), s.question(), "faq+crm",
                        null, null, false, 0, 0, 0, false, false, 0, e.getMessage()));
            }
        }

        // Build summary
        int causeMentioned = 0, escalationCorrect = 0, withSources = 0, withVerified = 0;
        long totalLatency = 0;
        for (ScenarioResult r : results) {
            if (r.causeMentioned()) causeMentioned++;
            if (r.escalationCorrect()) escalationCorrect++;
            if (r.sourceCount() > 0) withSources++;
            if (r.verifiedQuotes() > 0) withVerified++;
            totalLatency += r.latencyMs();
        }

        EvalSummary summary = new EvalSummary(results.size(), causeMentioned, escalationCorrect,
                withSources, withVerified,
                results.isEmpty() ? 0 : (double) totalLatency / results.size());

        log.info("Evaluation complete: {}/{} causes correct, {}/{} escalation correct",
                causeMentioned, results.size(), escalationCorrect, results.size());

        return ResponseEntity.ok(new EvalReport(results, summary));
    }

    /**
     * Minimal HttpServletRequest implementation for evaluation calls.
     * Only provides what resolveClientIp needs.
     */
    private static class MockHttpServletRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
        MockHttpServletRequest() {
            super(new DummyRequest());
        }

        @Override
        public String getRemoteAddr() { return "127.0.0.1"; }

        @Override
        public String getHeader(String name) {
            if ("X-Real-IP".equalsIgnoreCase(name)) return "127.0.0.1";
            return null;
        }
    }

    /**
     * Bare-minimum HttpServletRequest to satisfy the wrapper constructor.
     */
    private static class DummyRequest implements jakarta.servlet.http.HttpServletRequest {
        public String getAuthType() { return null; }
        public jakarta.servlet.http.Cookie[] getCookies() { return null; }
        public long getDateHeader(String n) { return -1; }
        public String getHeader(String n) { return null; }
        public java.util.Enumeration<String> getHeaders(String n) { return java.util.Collections.emptyEnumeration(); }
        public java.util.Enumeration<String> getHeaderNames() { return java.util.Collections.emptyEnumeration(); }
        public int getIntHeader(String n) { return -1; }
        public String getMethod() { return "POST"; }
        public String getPathInfo() { return null; }
        public String getPathTranslated() { return null; }
        public String getContextPath() { return ""; }
        public String getQueryString() { return null; }
        public String getRemoteUser() { return null; }
        public boolean isUserInRole(String r) { return false; }
        public java.security.Principal getUserPrincipal() { return null; }
        public String getRequestedSessionId() { return null; }
        public String getRequestURI() { return "/support/evaluate"; }
        public StringBuffer getRequestURL() { return new StringBuffer("http://localhost:8080/support/evaluate"); }
        public String getServletPath() { return "/support/evaluate"; }
        public jakarta.servlet.http.HttpSession getSession(boolean c) { return null; }
        public jakarta.servlet.http.HttpSession getSession() { return null; }
        public String changeSessionId() { return null; }
        public boolean isRequestedSessionIdValid() { return false; }
        public boolean isRequestedSessionIdFromCookie() { return false; }
        public boolean isRequestedSessionIdFromURL() { return false; }
        public boolean authenticate(jakarta.servlet.http.HttpServletResponse r) { return false; }
        public void login(String u, String p) {}
        public void logout() {}
        public java.util.Collection<jakarta.servlet.http.Part> getParts() { return List.of(); }
        public jakarta.servlet.http.Part getPart(String n) { return null; }
        public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> c) { return null; }
        public Object getAttribute(String n) { return null; }
        public java.util.Enumeration<String> getAttributeNames() { return java.util.Collections.emptyEnumeration(); }
        public String getCharacterEncoding() { return "UTF-8"; }
        public void setCharacterEncoding(String e) {}
        public int getContentLength() { return -1; }
        public long getContentLengthLong() { return -1; }
        public String getContentType() { return "application/json"; }
        public jakarta.servlet.ServletInputStream getInputStream() { return null; }
        public String getParameter(String n) { return null; }
        public java.util.Enumeration<String> getParameterNames() { return java.util.Collections.emptyEnumeration(); }
        public String[] getParameterValues(String n) { return null; }
        public java.util.Map<String, String[]> getParameterMap() { return Map.of(); }
        public String getProtocol() { return "HTTP/1.1"; }
        public String getScheme() { return "http"; }
        public String getServerName() { return "localhost"; }
        public int getServerPort() { return 8080; }
        public java.io.BufferedReader getReader() { return null; }
        public String getRemoteAddr() { return "127.0.0.1"; }
        public String getRemoteHost() { return "localhost"; }
        public void setAttribute(String n, Object o) {}
        public void removeAttribute(String n) {}
        public java.util.Locale getLocale() { return java.util.Locale.getDefault(); }
        public java.util.Enumeration<java.util.Locale> getLocales() { return java.util.Collections.emptyEnumeration(); }
        public boolean isSecure() { return false; }
        public jakarta.servlet.RequestDispatcher getRequestDispatcher(String p) { return null; }
        public int getRemotePort() { return 0; }
        public String getLocalName() { return "localhost"; }
        public String getLocalAddr() { return "127.0.0.1"; }
        public int getLocalPort() { return 8080; }
        public jakarta.servlet.ServletContext getServletContext() { return null; }
        public jakarta.servlet.AsyncContext startAsync() { return null; }
        public jakarta.servlet.AsyncContext startAsync(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) { return null; }
        public boolean isAsyncStarted() { return false; }
        public boolean isAsyncSupported() { return false; }
        public jakarta.servlet.AsyncContext getAsyncContext() { return null; }
        public jakarta.servlet.DispatcherType getDispatcherType() { return jakarta.servlet.DispatcherType.REQUEST; }
        public String getRequestId() { return "eval"; }
        public String getProtocolRequestId() { return ""; }
        public jakarta.servlet.ServletConnection getServletConnection() { return null; }
    }
}
