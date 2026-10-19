package com.crispyland.web.gatekeeper;

import com.crispyland.web.gatekeeper.GatekeeperEvent.EventType;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Append-only JSONL event log with SHA-256 hash chain.
 * One instance per application, managing all sessions under a root data directory.
 * <p>
 * Thread safety: a per-session {@link ReentrantLock} protects appending events, assigning
 * sequence numbers, computing hashes, and updating the snapshot file.
 */
public class GatekeeperEventLog {

    private static final Logger log = LoggerFactory.getLogger(GatekeeperEventLog.class);
    private final Path dataDir;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    /** Tracks the last seq and hash for a session to avoid re-reading the file on every append. */
    record SessionState(int lastSeq, String lastHash) {}

    public GatekeeperEventLog(Path dataDir) {
        this.dataDir = dataDir;
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Append an event to the session's event log.
     * The seq, eventId, timestamp, prevHash and hash are assigned here — caller provides the rest.
     */
    public GatekeeperEvent append(String sessionId, String studentId, int gate,
                                   EventType type, JsonNode payload) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        lock.lock();
        try {
            Path sessionDir = dataDir.resolve("sessions").resolve(sessionId);
            Files.createDirectories(sessionDir);
            Path eventsFile = sessionDir.resolve("events.jsonl");

            SessionState state = sessionStates.get(sessionId);
            if (state == null) {
                state = loadLastState(eventsFile);
            }

            int seq = state.lastSeq() + 1;
            String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Instant timestamp = Instant.now();
            String prevHash = state.lastHash();

            String hash = computeHash(seq, eventId, sessionId, studentId, timestamp,
                    gate, type, payload, prevHash);

            GatekeeperEvent event = new GatekeeperEvent(
                    seq, eventId, sessionId, studentId, timestamp, gate, type, payload, prevHash, hash);

            String line = mapper.writeValueAsString(event);
            try (BufferedWriter w = Files.newBufferedWriter(eventsFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
                w.newLine();
            }

            sessionStates.put(sessionId, new SessionState(seq, hash));
            return event;
        } catch (IOException e) {
            throw new RuntimeException("Failed to append event for session " + sessionId, e);
        } finally {
            lock.unlock();
        }
    }

    /** Read all events for a session, in order. */
    public List<GatekeeperEvent> readAll(String sessionId) {
        Path eventsFile = dataDir.resolve("sessions").resolve(sessionId).resolve("events.jsonl");
        if (!Files.exists(eventsFile)) {
            return List.of();
        }
        List<GatekeeperEvent> events = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(eventsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    events.add(mapper.readValue(line, GatekeeperEvent.class));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read events for session " + sessionId, e);
        }
        return events;
    }

    /** Verify the hash chain for a session. Returns an error message or null if valid. */
    public String verifyChain(String sessionId) {
        List<GatekeeperEvent> events = readAll(sessionId);
        String prevHash = "";
        for (GatekeeperEvent event : events) {
            if (!prevHash.equals(event.prevHash())) {
                return "Event seq=" + event.seq() + " has prevHash=" + event.prevHash()
                        + " but expected " + prevHash;
            }
            String expectedHash = computeHash(event.seq(), event.eventId(), event.sessionId(),
                    event.studentId(), event.timestamp(), event.gate(), event.type(),
                    event.payload(), event.prevHash());
            if (!expectedHash.equals(event.hash())) {
                return "Event seq=" + event.seq() + " has hash=" + event.hash()
                        + " but computed " + expectedHash;
            }
            prevHash = event.hash();
        }
        return null;
    }

    /** Rebuild a session snapshot from the event log. */
    public GatekeeperSession rebuildSnapshot(String sessionId) {
        List<GatekeeperEvent> events = readAll(sessionId);
        return GatekeeperSession.fromEvents(events);
    }

    /** Save the snapshot to disk for fast loading. */
    public void saveSnapshot(String sessionId, GatekeeperSession session) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        lock.lock();
        try {
            Path snapshotFile = dataDir.resolve("sessions").resolve(sessionId).resolve("snapshot.json");
            Path tmp = snapshotFile.resolveSibling("snapshot.json.tmp");
            mapper.writeValue(tmp.toFile(), session);
            Files.move(tmp, snapshotFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to save snapshot for session {}: {}", sessionId, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** Load snapshot if it exists, otherwise rebuild from events. */
    public GatekeeperSession loadSession(String sessionId) {
        Path snapshotFile = dataDir.resolve("sessions").resolve(sessionId).resolve("snapshot.json");
        if (Files.exists(snapshotFile)) {
            try {
                return mapper.readValue(snapshotFile.toFile(), GatekeeperSession.class);
            } catch (Exception e) {
                log.warn("Corrupt snapshot for {}, rebuilding from events", sessionId);
            }
        }
        Path eventsFile = dataDir.resolve("sessions").resolve(sessionId).resolve("events.jsonl");
        if (!Files.exists(eventsFile)) {
            return null;
        }
        return rebuildSnapshot(sessionId);
    }

    /** List all session IDs that have event logs. */
    public List<String> listSessionIds() {
        Path sessionsDir = dataDir.resolve("sessions");
        if (!Files.exists(sessionsDir)) {
            return List.of();
        }
        try (var stream = Files.list(sessionsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("events.jsonl")))
                    .map(d -> d.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list sessions: {}", e.getMessage());
            return List.of();
        }
    }

    /** Check if a session directory exists. */
    public boolean sessionExists(String sessionId) {
        return Files.exists(dataDir.resolve("sessions").resolve(sessionId).resolve("events.jsonl"));
    }

    // ── Hashing ─────────────────────────────────────────────────────────────

    /**
     * Compute SHA-256 hash from canonical JSON with stable (alphabetical) key ordering.
     * Includes all event fields except {@code hash}.
     */
    String computeHash(int seq, String eventId, String sessionId, String studentId,
                       Instant timestamp, int gate, EventType type,
                       JsonNode payload, String prevHash) {
        // Build canonical JSON with alphabetical key ordering using TreeMap
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("eventId", eventId);
        canonical.put("gate", gate);
        canonical.put("payload", payload);
        canonical.put("prevHash", prevHash);
        canonical.put("seq", seq);
        canonical.put("sessionId", sessionId);
        canonical.put("studentId", studentId);
        canonical.put("timestamp", timestamp.toString());
        canonical.put("type", type.name());

        try {
            // Serialize the TreeMap — Jackson writes keys in iteration order, which is sorted
            ObjectNode node = mapper.valueToTree(canonical);
            // Sort payload keys too if it's an object
            if (payload != null && payload.isObject()) {
                ObjectNode sortedPayload = sortObjectNode(payload);
                node.set("payload", sortedPayload);
            }
            String json = mapper.writeValueAsString(node);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private ObjectNode sortObjectNode(JsonNode node) {
        ObjectNode sorted = mapper.createObjectNode();
        TreeMap<String, JsonNode> entries = new TreeMap<>();
        node.propertyNames().forEach(name -> entries.put(name, node.get(name)));
        entries.forEach(sorted::set);
        return sorted;
    }

    private SessionState loadLastState(Path eventsFile) {
        if (!Files.exists(eventsFile)) {
            return new SessionState(0, "");
        }
        // Read the last line to get the last seq and hash
        try (BufferedReader r = Files.newBufferedReader(eventsFile, StandardCharsets.UTF_8)) {
            String line;
            String lastLine = null;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    lastLine = line;
                }
            }
            if (lastLine == null) {
                return new SessionState(0, "");
            }
            GatekeeperEvent last = mapper.readValue(lastLine, GatekeeperEvent.class);
            return new SessionState(last.seq(), last.hash());
        } catch (IOException e) {
            log.warn("Failed to read last event state from {}: {}", eventsFile, e.getMessage());
            return new SessionState(0, "");
        }
    }
}
