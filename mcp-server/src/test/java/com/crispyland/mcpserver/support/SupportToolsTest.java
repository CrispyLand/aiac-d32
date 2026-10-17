package com.crispyland.mcpserver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SupportToolsTest {

    @TempDir
    Path directory;

    private SupportTools tools;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws IOException {
        mapper = JsonMapper.builder().build();

        Files.writeString(directory.resolve("users.json"), """
                [{"id":"USR-001","displayName":"Alice","email":"a@example.com","plan":"pro",
                  "accountStatus":"active","timezone":"UTC",
                  "integrations":{"googleCalendar":{"connected":true,"lastError":null},
                  "googleTasks":{"connected":true,"lastError":null},
                  "telegram":{"linked":true,"chatId":"123"}}}]""");

        Files.writeString(directory.resolve("tickets.json"), """
                [{"id":"TKT-001","userId":"USR-001","subject":"Test","description":"Desc",
                  "status":"open","priority":"medium","category":"other",
                  "createdAt":"2026-10-15T09:00:00Z","updatedAt":"2026-10-15T09:00:00Z",
                  "environment":{},"messages":[]}]""");

        SupportStore store = new SupportStore(mapper, directory);
        tools = new SupportTools(store, mapper);
    }

    @Test
    void getTicketReturnsJson() throws Exception {
        String result = tools.getTicket("TKT-001");
        JsonNode node = mapper.readTree(result);
        assertThat(node.path("id").stringValue()).isEqualTo("TKT-001");
        assertThat(node.path("subject").stringValue()).isEqualTo("Test");
    }

    @Test
    void getTicketNotFoundReturnsJsonError() throws Exception {
        String result = tools.getTicket("TKT-999");
        JsonNode node = mapper.readTree(result);
        assertThat(node.has("error")).isTrue();
    }

    @Test
    void getUserReturnsJson() throws Exception {
        String result = tools.getUser("USR-001");
        JsonNode node = mapper.readTree(result);
        assertThat(node.path("displayName").stringValue()).isEqualTo("Alice");
    }

    @Test
    void getUserNotFoundReturnsJsonError() throws Exception {
        String result = tools.getUser("USR-999");
        JsonNode node = mapper.readTree(result);
        assertThat(node.has("error")).isTrue();
    }

    @Test
    void listTicketsReturnsJsonArray() throws Exception {
        String result = tools.listTickets(null, null);
        JsonNode node = mapper.readTree(result);
        assertThat(node.isArray()).isTrue();
        assertThat(node.size()).isEqualTo(1);
    }

    @Test
    void listTicketsInvalidStatusReturnsError() throws Exception {
        String result = tools.listTickets("invalid", null);
        JsonNode node = mapper.readTree(result);
        assertThat(node.has("error")).isTrue();
    }

    @Test
    void createTicketValidatesRequiredFields() throws Exception {
        // Missing userId
        String r1 = tools.createTicket(null, "subj", "desc", "other", "low", null, null);
        assertThat(mapper.readTree(r1).has("error")).isTrue();

        // Missing subject
        String r2 = tools.createTicket("USR-001", null, "desc", "other", "low", null, null);
        assertThat(mapper.readTree(r2).has("error")).isTrue();

        // Missing description
        String r3 = tools.createTicket("USR-001", "subj", null, "other", "low", null, null);
        assertThat(mapper.readTree(r3).has("error")).isTrue();
    }

    @Test
    void createTicketValidatesEnums() throws Exception {
        String r1 = tools.createTicket("USR-001", "subj", "desc", "badcategory", "low", null, null);
        assertThat(mapper.readTree(r1).has("error")).isTrue();

        String r2 = tools.createTicket("USR-001", "subj", "desc", "other", "badpriority", null, null);
        assertThat(mapper.readTree(r2).has("error")).isTrue();
    }

    @Test
    void createTicketValidatesLength() throws Exception {
        String longSubject = "x".repeat(201);
        String r = tools.createTicket("USR-001", longSubject, "desc", "other", "low", null, null);
        assertThat(mapper.readTree(r).has("error")).isTrue();

        String longDesc = "x".repeat(5001);
        String r2 = tools.createTicket("USR-001", "subj", longDesc, "other", "low", null, null);
        assertThat(mapper.readTree(r2).has("error")).isTrue();
    }

    @Test
    void createTicketReturnsCreatedTicketAsJson() throws Exception {
        String result = tools.createTicket("USR-001", "New", "Details", "integration", "high", "Chrome", "0.0.1");
        JsonNode node = mapper.readTree(result);
        assertThat(node.path("id").stringValue()).isEqualTo("TKT-002");
        assertThat(node.path("status").stringValue()).isEqualTo("open");
        assertThat(node.path("category").stringValue()).isEqualTo("integration");
    }

    @Test
    void searchTicketsReturnsJson() throws Exception {
        String result = tools.searchTickets("test", null);
        JsonNode node = mapper.readTree(result);
        assertThat(node.isArray()).isTrue();
        assertThat(node.size()).isEqualTo(1);
    }
}
