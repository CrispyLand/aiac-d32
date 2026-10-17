package com.crispyland.mcpserver.support;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP tools for the support CRM. All methods return JSON strings so the caller
 * can parse structured data directly rather than extracting facts from prose.
 */
@Service
public class SupportTools {

    private static final Logger log = LoggerFactory.getLogger(SupportTools.class);

    private static final Set<String> VALID_STATUSES = Set.of("open", "pending", "closed");
    private static final Set<String> VALID_PRIORITIES = Set.of("low", "medium", "high", "urgent");
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "integration", "briefing", "rag", "performance", "account", "other");

    private static final int MAX_SUBJECT = 200;
    private static final int MAX_DESCRIPTION = 5000;

    private final SupportStore store;
    private final ObjectMapper mapper;

    public SupportTools(SupportStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @McpTool(name = "getTicket",
            description = "Get a support ticket by ID. Returns the full ticket as JSON including "
                    + "messages, environment, and timestamps.")
    public String getTicket(
            @McpToolParam(description = "Ticket ID, e.g. TKT-001", required = true)
            String ticketId) {
        log.info("getTicket: {}", ticketId);
        return store.getTicket(ticketId)
                .map(this::toJson)
                .orElse("{\"error\":\"Ticket not found: " + sanitize(ticketId) + "\"}");
    }

    @McpTool(name = "getUser",
            description = "Get a support user by ID. Returns user profile as JSON including plan, "
                    + "timezone, account status, and integration connection states.")
    public String getUser(
            @McpToolParam(description = "User ID, e.g. USR-001", required = true)
            String userId) {
        log.info("getUser: {}", userId);
        return store.getUser(userId)
                .map(this::toJson)
                .orElse("{\"error\":\"User not found: " + sanitize(userId) + "\"}");
    }

    @McpTool(name = "listTickets",
            description = "List support tickets, optionally filtered by status. Returns a JSON array.")
    public String listTickets(
            @McpToolParam(description = "Filter by status: open, pending, or closed. Omit for all.", required = false)
            String status,
            @McpToolParam(description = "Maximum number of tickets to return. Default 50.", required = false)
            Integer limit) {
        log.info("listTickets: status={}, limit={}", status, limit);
        if (status != null && !status.isBlank() && !VALID_STATUSES.contains(status.toLowerCase())) {
            return "{\"error\":\"Invalid status. Must be one of: " + VALID_STATUSES + "\"}";
        }
        int cap = (limit == null || limit <= 0) ? 50 : Math.min(limit, 100);
        return toJson(store.ticketsByStatus(status, cap));
    }

    @McpTool(name = "listUserTickets",
            description = "List tickets for a specific user, optionally filtered by status. Returns a JSON array.")
    public String listUserTickets(
            @McpToolParam(description = "User ID, e.g. USR-001", required = true)
            String userId,
            @McpToolParam(description = "Filter by status: open, pending, or closed. Omit for all.", required = false)
            String status) {
        log.info("listUserTickets: userId={}, status={}", userId, status);
        if (status != null && !status.isBlank() && !VALID_STATUSES.contains(status.toLowerCase())) {
            return "{\"error\":\"Invalid status. Must be one of: " + VALID_STATUSES + "\"}";
        }
        return toJson(store.ticketsByUser(userId, status));
    }

    @McpTool(name = "searchTickets",
            description = "Search tickets by keyword in subject, description, or category. Returns a JSON array.")
    public String searchTickets(
            @McpToolParam(description = "Search query", required = true)
            String query,
            @McpToolParam(description = "Maximum results. Default 20.", required = false)
            Integer limit) {
        log.info("searchTickets: query='{}', limit={}", query, limit);
        int cap = (limit == null || limit <= 0) ? 20 : Math.min(limit, 50);
        return toJson(store.searchTickets(query, cap));
    }

    @McpTool(name = "createTicket",
            description = "Create a new support ticket. Returns the created ticket as JSON with "
                    + "the generated ID.")
    public String createTicket(
            @McpToolParam(description = "User ID for the ticket owner", required = true)
            String userId,
            @McpToolParam(description = "Short summary of the issue (max 200 chars)", required = true)
            String subject,
            @McpToolParam(description = "Detailed description (max 5000 chars)", required = true)
            String description,
            @McpToolParam(description = "Category: integration, briefing, rag, performance, account, or other", required = true)
            String category,
            @McpToolParam(description = "Priority: low, medium, high, or urgent", required = true)
            String priority,
            @McpToolParam(description = "Browser info, e.g. 'Chrome 130'", required = false)
            String browser,
            @McpToolParam(description = "App version, e.g. '0.0.1'", required = false)
            String appVersion) {

        log.info("createTicket: userId={}, subject='{}', category={}, priority={}",
                userId, subject, category, priority);

        if (userId == null || userId.isBlank()) {
            return "{\"error\":\"userId is required\"}";
        }
        if (subject == null || subject.isBlank()) {
            return "{\"error\":\"subject is required\"}";
        }
        if (subject.length() > MAX_SUBJECT) {
            return "{\"error\":\"subject must be " + MAX_SUBJECT + " characters or fewer\"}";
        }
        if (description == null || description.isBlank()) {
            return "{\"error\":\"description is required\"}";
        }
        if (description.length() > MAX_DESCRIPTION) {
            return "{\"error\":\"description must be " + MAX_DESCRIPTION + " characters or fewer\"}";
        }
        if (category == null || !VALID_CATEGORIES.contains(category.toLowerCase())) {
            return "{\"error\":\"Invalid category. Must be one of: " + VALID_CATEGORIES + "\"}";
        }
        if (priority == null || !VALID_PRIORITIES.contains(priority.toLowerCase())) {
            return "{\"error\":\"Invalid priority. Must be one of: " + VALID_PRIORITIES + "\"}";
        }

        SupportTicket.Environment env = (browser != null || appVersion != null)
                ? new SupportTicket.Environment(
                        browser != null ? browser : "",
                        appVersion != null ? appVersion : "")
                : null;

        SupportTicket ticket = store.createTicket(userId, subject, description,
                category.toLowerCase(), priority.toLowerCase(), env);
        return toJson(ticket);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("Failed to serialize to JSON", e);
            return "{\"error\":\"Internal serialization error\"}";
        }
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[\"\\\\]", "").substring(0, Math.min(input.length(), 50));
    }
}
