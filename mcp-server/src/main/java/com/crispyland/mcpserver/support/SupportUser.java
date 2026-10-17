package com.crispyland.mcpserver.support;

public record SupportUser(
        String id,
        String displayName,
        String email,
        String plan,
        String accountStatus,
        String timezone,
        Integrations integrations) {

    public record Integrations(
            CalendarStatus googleCalendar,
            CalendarStatus googleTasks,
            TelegramStatus telegram) {}

    public record CalendarStatus(boolean connected, String lastError) {}

    public record TelegramStatus(boolean linked, String chatId) {}
}
