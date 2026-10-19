package com.crispyland.web.gatekeeper;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.web.gatekeeper.GatekeeperEvent.EventType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GatekeeperEventLogTest {

    @TempDir
    Path tempDir;

    private GatekeeperEventLog eventLog;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        eventLog = new GatekeeperEventLog(tempDir);
        mapper = JsonMapper.builder().build();
    }

    @Test
    void appendAndReadBack() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("studentId", "stu-01");

        GatekeeperEvent event = eventLog.append("sess1", "stu-01", 0,
                EventType.SESSION_STARTED, payload);

        assertThat(event.seq()).isEqualTo(1);
        assertThat(event.sessionId()).isEqualTo("sess1");
        assertThat(event.eventId()).startsWith("evt_");
        assertThat(event.hash()).isNotBlank();
        assertThat(event.prevHash()).isEmpty();

        List<GatekeeperEvent> events = eventLog.readAll("sess1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).hash()).isEqualTo(event.hash());
    }

    @Test
    void sequenceIncrementsAndHashChains() {
        ObjectNode p1 = mapper.createObjectNode();
        p1.put("content", "hello");
        ObjectNode p2 = mapper.createObjectNode();
        p2.put("content", "world");

        GatekeeperEvent e1 = eventLog.append("sess2", "stu-01", 0,
                EventType.STUDENT_MESSAGE, p1);
        GatekeeperEvent e2 = eventLog.append("sess2", "stu-01", 0,
                EventType.TUTOR_MESSAGE, p2);

        assertThat(e1.seq()).isEqualTo(1);
        assertThat(e2.seq()).isEqualTo(2);
        assertThat(e2.prevHash()).isEqualTo(e1.hash());
    }

    @Test
    void verifyChainPassesForValidLog() {
        ObjectNode p = mapper.createObjectNode();
        p.put("test", true);

        eventLog.append("sess3", "stu-01", 0, EventType.SESSION_STARTED, p);
        eventLog.append("sess3", "stu-01", 0, EventType.STUDENT_MESSAGE, p);
        eventLog.append("sess3", "stu-01", 0, EventType.TUTOR_MESSAGE, p);

        String error = eventLog.verifyChain("sess3");
        assertThat(error).isNull();
    }

    @Test
    void rebuildSnapshotFromEvents() {
        ObjectNode startPayload = mapper.createObjectNode();
        startPayload.put("studentId", "stu-01");
        eventLog.append("sess4", "stu-01", 0, EventType.SESSION_STARTED, startPayload);

        ObjectNode msgPayload = mapper.createObjectNode();
        msgPayload.put("content", "I want to start a cafe");
        eventLog.append("sess4", "stu-01", 0, EventType.STUDENT_MESSAGE, msgPayload);

        ObjectNode tutorPayload = mapper.createObjectNode();
        tutorPayload.put("content", "Tell me more about that.");
        tutorPayload.put("source", "llm");
        eventLog.append("sess4", "stu-01", 0, EventType.TUTOR_MESSAGE, tutorPayload);

        GatekeeperSession session = eventLog.rebuildSnapshot("sess4");
        assertThat(session.getStudentId()).isEqualTo("stu-01");
        assertThat(session.getCurrentGate()).isEqualTo(0);
        assertThat(session.currentConversation()).hasSize(2);
    }

    @Test
    void snapshotSaveAndLoad() {
        ObjectNode startPayload = mapper.createObjectNode();
        startPayload.put("studentId", "stu-01");
        eventLog.append("sess5", "stu-01", 0, EventType.SESSION_STARTED, startPayload);

        GatekeeperSession session = eventLog.rebuildSnapshot("sess5");
        eventLog.saveSnapshot("sess5", session);

        GatekeeperSession loaded = eventLog.loadSession("sess5");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getStudentId()).isEqualTo("stu-01");
    }

    @Test
    void sessionExistsReturnsFalseForUnknown() {
        assertThat(eventLog.sessionExists("nonexistent")).isFalse();
    }

    @Test
    void separateSessionsDoNotInterfere() {
        ObjectNode p = mapper.createObjectNode();
        p.put("content", "test");

        eventLog.append("sessA", "stu-01", 0, EventType.SESSION_STARTED, p);
        eventLog.append("sessA", "stu-01", 0, EventType.STUDENT_MESSAGE, p);

        eventLog.append("sessB", "stu-02", 0, EventType.SESSION_STARTED, p);

        assertThat(eventLog.readAll("sessA")).hasSize(2);
        assertThat(eventLog.readAll("sessB")).hasSize(1);
    }
}
