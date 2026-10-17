package com.crispyland.mcpserver.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SupportStoreTest {

    @TempDir
    Path directory;

    @BeforeEach
    void seedFiles() throws IOException {
        Files.writeString(directory.resolve("users.json"), """
                [
                  {"id":"USR-001","displayName":"Alice","email":"alice@example.com","plan":"pro",
                   "accountStatus":"active","timezone":"Asia/Shanghai",
                   "integrations":{"googleCalendar":{"connected":true,"lastError":null},
                   "googleTasks":{"connected":true,"lastError":null},
                   "telegram":{"linked":true,"chatId":"12345"}}},
                  {"id":"USR-002","displayName":"Bob","email":"bob@example.com","plan":"free",
                   "accountStatus":"active","timezone":"America/New_York",
                   "integrations":{"googleCalendar":{"connected":true,"lastError":"invalid_grant"},
                   "googleTasks":{"connected":false,"lastError":null},
                   "telegram":{"linked":false,"chatId":null}}}
                ]""");

        Files.writeString(directory.resolve("tickets.json"), """
                [
                  {"id":"TKT-001","userId":"USR-001","subject":"Calendar broken",
                   "description":"Events not showing","status":"open","priority":"high",
                   "category":"integration","createdAt":"2026-10-15T09:00:00Z",
                   "updatedAt":"2026-10-15T09:00:00Z","environment":{"browser":"Chrome 130","appVersion":"0.0.1"},
                   "messages":[{"from":"user","at":"2026-10-15T09:00:00Z","text":"Help"}]},
                  {"id":"TKT-002","userId":"USR-002","subject":"Slow response",
                   "description":"Takes too long","status":"pending","priority":"medium",
                   "category":"performance","createdAt":"2026-10-14T10:00:00Z",
                   "updatedAt":"2026-10-14T10:00:00Z","environment":{},"messages":[]}
                ]""");
    }

    private SupportStore store() {
        return new SupportStore(JsonMapper.builder().build(), directory);
    }

    @Test
    void loadsUsersAndTickets() {
        SupportStore s = store();
        assertThat(s.allUsers()).hasSize(2);
        assertThat(s.allTickets()).hasSize(2);
    }

    @Test
    void getUserReturnsCorrectUser() {
        Optional<SupportUser> alice = store().getUser("USR-001");
        assertThat(alice).isPresent();
        assertThat(alice.get().displayName()).isEqualTo("Alice");
        assertThat(alice.get().plan()).isEqualTo("pro");
        assertThat(alice.get().integrations().googleCalendar().connected()).isTrue();
    }

    @Test
    void getUserReturnsEmptyForUnknownId() {
        assertThat(store().getUser("USR-999")).isEmpty();
    }

    @Test
    void getTicketReturnsCorrectTicket() {
        Optional<SupportTicket> t = store().getTicket("TKT-001");
        assertThat(t).isPresent();
        assertThat(t.get().subject()).isEqualTo("Calendar broken");
        assertThat(t.get().messages()).hasSize(1);
    }

    @Test
    void ticketsByStatusFilters() {
        SupportStore s = store();
        assertThat(s.ticketsByStatus("open", 50)).hasSize(1);
        assertThat(s.ticketsByStatus("pending", 50)).hasSize(1);
        assertThat(s.ticketsByStatus("closed", 50)).isEmpty();
        assertThat(s.ticketsByStatus(null, 50)).hasSize(2);
    }

    @Test
    void ticketsByUserFilters() {
        SupportStore s = store();
        assertThat(s.ticketsByUser("USR-001", null)).hasSize(1);
        assertThat(s.ticketsByUser("USR-001", "open")).hasSize(1);
        assertThat(s.ticketsByUser("USR-001", "closed")).isEmpty();
    }

    @Test
    void searchTicketsMatchesSubjectAndDescription() {
        SupportStore s = store();
        assertThat(s.searchTickets("calendar", 10)).hasSize(1);
        assertThat(s.searchTickets("slow", 10)).hasSize(1);
        assertThat(s.searchTickets("nonexistent", 10)).isEmpty();
    }

    @Test
    void createTicketGeneratesIncrementalId() {
        SupportStore s = store();
        SupportTicket created = s.createTicket("USR-001", "New issue", "Details",
                "other", "low", null);
        assertThat(created.id()).isEqualTo("TKT-003");
        assertThat(created.status()).isEqualTo("open");
        assertThat(s.allTickets()).hasSize(3);
    }

    @Test
    void createTicketSurvivesRestart() {
        store().createTicket("USR-001", "Persistent", "Should survive", "other", "low", null);

        SupportStore restarted = store();
        assertThat(restarted.allTickets()).hasSize(3);
        assertThat(restarted.getTicket("TKT-003")).isPresent();
        assertThat(restarted.getTicket("TKT-003").get().subject()).isEqualTo("Persistent");
    }

    @Test
    void addMessageUpdatesTicket() {
        SupportStore s = store();
        SupportTicket updated = s.addMessage("TKT-001", "support", "We're looking into it.");
        assertThat(updated.messages()).hasSize(2);
        assertThat(updated.messages().get(1).text()).isEqualTo("We're looking into it.");
    }

    @Test
    void addMessageToNonexistentTicketReturnsNull() {
        assertThat(store().addMessage("TKT-999", "support", "test")).isNull();
    }

    @Test
    void corruptFileStartsEmpty() throws IOException {
        Files.writeString(directory.resolve("tickets.json"), "not json at all");
        SupportStore s = store();
        assertThat(s.allTickets()).isEmpty();
    }

    @Test
    void missingFilesStartEmpty() throws IOException {
        Path empty = directory.resolve("empty");
        Files.createDirectories(empty);
        SupportStore s = new SupportStore(JsonMapper.builder().build(), empty);
        assertThat(s.allUsers()).isEmpty();
        assertThat(s.allTickets()).isEmpty();
    }

    @Test
    void concurrentTicketCreation() throws InterruptedException {
        SupportStore s = store();
        Thread t1 = new Thread(() -> s.createTicket("USR-001", "A", "desc", "other", "low", null));
        Thread t2 = new Thread(() -> s.createTicket("USR-002", "B", "desc", "other", "low", null));
        t1.start(); t2.start();
        t1.join(); t2.join();

        assertThat(s.allTickets()).hasSize(4);
        // IDs should be unique
        long uniqueIds = s.allTickets().stream().map(SupportTicket::id).distinct().count();
        assertThat(uniqueIds).isEqualTo(4);
    }
}
