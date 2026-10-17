package com.crispyland.mcpserver.support;

import java.time.Instant;
import java.util.List;

public record SupportTicket(
        String id,
        String userId,
        String subject,
        String description,
        String status,
        String priority,
        String category,
        Instant createdAt,
        Instant updatedAt,
        Environment environment,
        List<Message> messages) {

    public record Environment(String browser, String appVersion) {}

    public record Message(String from, Instant at, String text) {}
}
