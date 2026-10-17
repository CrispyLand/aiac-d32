package com.crispyland.mcpserver.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loads users.json and tickets.json into memory at construction, persists ticket
 * mutations via atomic temp-file-then-rename writes.
 */
public class SupportStore {

    private static final Logger log = LoggerFactory.getLogger(SupportStore.class);

    private final ObjectMapper mapper;
    private final ObjectWriter writer;
    private final Path usersFile;
    private final Path ticketsFile;

    private final Map<String, SupportUser> users = new LinkedHashMap<>();
    private final Map<String, SupportTicket> tickets = new LinkedHashMap<>();

    public SupportStore(ObjectMapper mapper, Path directory) {
        this.mapper = mapper;
        this.writer = mapper.writerWithDefaultPrettyPrinter();
        this.usersFile = directory.resolve("users.json");
        this.ticketsFile = directory.resolve("tickets.json");
        loadUsers();
        loadTickets();
    }

    public Optional<SupportUser> getUser(String id) {
        return Optional.ofNullable(users.get(id));
    }

    public List<SupportUser> allUsers() {
        return List.copyOf(users.values());
    }

    public synchronized Optional<SupportTicket> getTicket(String id) {
        return Optional.ofNullable(tickets.get(id));
    }

    public synchronized List<SupportTicket> allTickets() {
        return List.copyOf(tickets.values());
    }

    public synchronized List<SupportTicket> ticketsByStatus(String status, int limit) {
        return tickets.values().stream()
                .filter(t -> status == null || status.isBlank() || t.status().equalsIgnoreCase(status))
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
    }

    public synchronized List<SupportTicket> ticketsByUser(String userId, String status) {
        return tickets.values().stream()
                .filter(t -> t.userId().equals(userId))
                .filter(t -> status == null || status.isBlank() || t.status().equalsIgnoreCase(status))
                .toList();
    }

    public synchronized List<SupportTicket> searchTickets(String query, int limit) {
        if (query == null || query.isBlank()) return allTickets();
        String lower = query.toLowerCase(Locale.ROOT);
        return tickets.values().stream()
                .filter(t -> matches(t, lower))
                .limit(limit > 0 ? limit : 20)
                .toList();
    }

    public synchronized SupportTicket createTicket(String userId, String subject,
            String description, String category, String priority,
            SupportTicket.Environment environment) {
        String id = nextTicketId();
        Instant now = Instant.now();
        var ticket = new SupportTicket(id, userId, subject, description,
                "open", priority, category, now, now, environment, List.of());
        tickets.put(id, ticket);
        writeTickets();
        log.info("Created ticket {} for user {}", id, userId);
        return ticket;
    }

    public synchronized SupportTicket addMessage(String ticketId, String from, String text) {
        SupportTicket existing = tickets.get(ticketId);
        if (existing == null) return null;

        var messages = new ArrayList<>(existing.messages());
        messages.add(new SupportTicket.Message(from, Instant.now(), text));
        var updated = new SupportTicket(existing.id(), existing.userId(), existing.subject(),
                existing.description(), existing.status(), existing.priority(),
                existing.category(), existing.createdAt(), Instant.now(),
                existing.environment(), List.copyOf(messages));
        tickets.put(ticketId, updated);
        writeTickets();
        return updated;
    }

    private String nextTicketId() {
        int max = tickets.keySet().stream()
                .filter(k -> k.startsWith("TKT-"))
                .map(k -> k.substring(4))
                .mapToInt(s -> { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } })
                .max()
                .orElse(0);
        return String.format("TKT-%03d", max + 1);
    }

    private boolean matches(SupportTicket t, String lower) {
        return t.id().toLowerCase(Locale.ROOT).contains(lower)
                || t.subject().toLowerCase(Locale.ROOT).contains(lower)
                || t.description().toLowerCase(Locale.ROOT).contains(lower)
                || t.category().toLowerCase(Locale.ROOT).contains(lower);
    }

    // --- Persistence ---

    private void writeTickets() {
        ArrayNode array = mapper.createArrayNode();
        for (SupportTicket t : tickets.values()) {
            array.add(ticketToJson(t));
        }
        atomicWrite(ticketsFile, array);
    }

    private ObjectNode ticketToJson(SupportTicket t) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", t.id());
        node.put("userId", t.userId());
        node.put("subject", t.subject());
        node.put("description", t.description());
        node.put("status", t.status());
        node.put("priority", t.priority());
        node.put("category", t.category());
        node.put("createdAt", t.createdAt().toString());
        node.put("updatedAt", t.updatedAt().toString());

        if (t.environment() != null) {
            ObjectNode env = node.putObject("environment");
            env.put("browser", t.environment().browser());
            env.put("appVersion", t.environment().appVersion());
        }

        ArrayNode msgs = node.putArray("messages");
        for (SupportTicket.Message m : t.messages()) {
            ObjectNode msg = msgs.addObject();
            msg.put("from", m.from());
            msg.put("at", m.at().toString());
            msg.put("text", m.text());
        }
        return node;
    }

    private void atomicWrite(Path file, JsonNode content) {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(temp, writer.writeValueAsString(content));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist to " + file.toAbsolutePath(), e);
        }
    }

    // --- Loading ---

    private void loadUsers() {
        if (!Files.isRegularFile(usersFile)) {
            log.warn("Users file not found: {} — starting with no users", usersFile.toAbsolutePath());
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(usersFile));
            if (!root.isArray()) return;
            for (JsonNode node : root) {
                SupportUser user = parseUser(node);
                if (user != null) users.put(user.id(), user);
            }
            log.info("Loaded {} user(s) from {}", users.size(), usersFile.toAbsolutePath());
        } catch (IOException | JacksonException e) {
            log.warn("Could not read users from {} ({}) — starting empty", usersFile.toAbsolutePath(), e.getMessage());
        }
    }

    private void loadTickets() {
        if (!Files.isRegularFile(ticketsFile)) {
            log.warn("Tickets file not found: {} — starting with no tickets", ticketsFile.toAbsolutePath());
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(ticketsFile));
            if (!root.isArray()) return;
            for (JsonNode node : root) {
                SupportTicket ticket = parseTicket(node);
                if (ticket != null) tickets.put(ticket.id(), ticket);
            }
            log.info("Loaded {} ticket(s) from {}", tickets.size(), ticketsFile.toAbsolutePath());
        } catch (IOException | JacksonException e) {
            log.warn("Could not read tickets from {} ({}) — starting empty", ticketsFile.toAbsolutePath(), e.getMessage());
        }
    }

    private SupportUser parseUser(JsonNode node) {
        String id = text(node, "id");
        if (id.isEmpty()) return null;

        JsonNode integ = node.path("integrations");
        var gcal = new SupportUser.CalendarStatus(
                integ.path("googleCalendar").path("connected").booleanValue(false),
                textOrNull(integ.path("googleCalendar"), "lastError"));
        var gtask = new SupportUser.CalendarStatus(
                integ.path("googleTasks").path("connected").booleanValue(false),
                textOrNull(integ.path("googleTasks"), "lastError"));
        var tg = new SupportUser.TelegramStatus(
                integ.path("telegram").path("linked").booleanValue(false),
                textOrNull(integ.path("telegram"), "chatId"));

        return new SupportUser(id, text(node, "displayName"), text(node, "email"),
                text(node, "plan"), text(node, "accountStatus"), text(node, "timezone"),
                new SupportUser.Integrations(gcal, gtask, tg));
    }

    private SupportTicket parseTicket(JsonNode node) {
        String id = text(node, "id");
        if (id.isEmpty()) return null;

        SupportTicket.Environment env = null;
        JsonNode envNode = node.path("environment");
        if (!envNode.isMissingNode()) {
            env = new SupportTicket.Environment(text(envNode, "browser"), text(envNode, "appVersion"));
        }

        List<SupportTicket.Message> messages = new ArrayList<>();
        for (JsonNode m : node.path("messages")) {
            messages.add(new SupportTicket.Message(
                    text(m, "from"), instant(m.path("at")), text(m, "text")));
        }

        return new SupportTicket(id, text(node, "userId"), text(node, "subject"),
                text(node, "description"), text(node, "status"), text(node, "priority"),
                text(node, "category"), instant(node.path("createdAt")), instant(node.path("updatedAt")),
                env, List.copyOf(messages));
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isTextual() ? child.stringValue() : "";
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isTextual() ? child.stringValue() : null;
    }

    private static Instant instant(JsonNode node) {
        if (!node.isTextual()) return Instant.EPOCH;
        try {
            return Instant.parse(node.stringValue());
        } catch (java.time.format.DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}
