package com.crispyland.web.gatekeeper;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.web.gatekeeper.GatekeeperEvent.EventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GatekeeperSessionTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void wordCountHandlesBlankAndNull() {
        assertThat(GatekeeperSession.wordCount(null)).isEqualTo(0);
        assertThat(GatekeeperSession.wordCount("")).isEqualTo(0);
        assertThat(GatekeeperSession.wordCount("   ")).isEqualTo(0);
        assertThat(GatekeeperSession.wordCount("one")).isEqualTo(1);
        assertThat(GatekeeperSession.wordCount("one two three")).isEqualTo(3);
        assertThat(GatekeeperSession.wordCount("  spaced   words  ")).isEqualTo(2);
    }

    @Test
    void fromEventsBuildsCorrectState() {
        List<GatekeeperEvent> events = new ArrayList<>();
        Instant now = Instant.now();

        ObjectNode startPayload = mapper.createObjectNode();
        startPayload.put("studentId", "stu-01");
        events.add(new GatekeeperEvent(1, "evt_1", "sess1", "stu-01", now,
                0, EventType.SESSION_STARTED, startPayload, "", "hash1"));

        ObjectNode msgPayload = mapper.createObjectNode();
        msgPayload.put("content", "I want a coffee shop");
        events.add(new GatekeeperEvent(2, "evt_2", "sess1", "stu-01", now.plusSeconds(1),
                0, EventType.STUDENT_MESSAGE, msgPayload, "hash1", "hash2"));

        GatekeeperSession session = GatekeeperSession.fromEvents(events);
        assertThat(session.getSessionId()).isEqualTo("sess1");
        assertThat(session.getStudentId()).isEqualTo("stu-01");
        assertThat(session.getCurrentGate()).isEqualTo(0);
        assertThat(session.getPassedGates()).isEmpty();
        assertThat(session.studentMessages(0)).containsExactly("I want a coffee shop");
    }

    @Test
    void gateUnlockAdvancesStateAndAccumulatesBusinessSummary() {
        List<GatekeeperEvent> events = new ArrayList<>();
        Instant now = Instant.now();

        ObjectNode startPayload = mapper.createObjectNode();
        events.add(new GatekeeperEvent(1, "evt_1", "sess1", "stu-01", now,
                0, EventType.SESSION_STARTED, startPayload, "", "h1"));

        ObjectNode unlock0 = mapper.createObjectNode();
        unlock0.put("gate", 0);
        unlock0.put("businessSummaryEntry", "A coffee shop in Adelaide");
        events.add(new GatekeeperEvent(2, "evt_2", "sess1", "stu-01", now.plusSeconds(10),
                0, EventType.GATE_UNLOCKED, unlock0, "h1", "h2"));

        ObjectNode unlock1 = mapper.createObjectNode();
        unlock1.put("gate", 1);
        unlock1.put("businessSummaryEntry", "Sole trader structure");
        events.add(new GatekeeperEvent(3, "evt_3", "sess1", "stu-01", now.plusSeconds(20),
                1, EventType.GATE_UNLOCKED, unlock1, "h2", "h3"));

        GatekeeperSession session = GatekeeperSession.fromEvents(events);
        assertThat(session.getCurrentGate()).isEqualTo(2);
        assertThat(session.getPassedGates()).containsExactly(0, 1);
        assertThat(session.getBusinessSummary()).contains("coffee shop");
        assertThat(session.getBusinessSummary()).contains("Sole trader");
    }

    @Test
    void judgeResultUpdatesNextFocus() {
        List<GatekeeperEvent> events = new ArrayList<>();
        Instant now = Instant.now();

        ObjectNode startPayload = mapper.createObjectNode();
        events.add(new GatekeeperEvent(1, "evt_1", "sess1", "stu-01", now,
                0, EventType.SESSION_STARTED, startPayload, "", "h1"));

        ObjectNode judgePayload = mapper.createObjectNode();
        judgePayload.put("nextFocus", "Explain why this location");
        events.add(new GatekeeperEvent(2, "evt_2", "sess1", "stu-01", now.plusSeconds(5),
                0, EventType.JUDGE_RESULT, judgePayload, "h1", "h2"));

        GatekeeperSession session = GatekeeperSession.fromEvents(events);
        assertThat(session.getLastJudgeNextFocus()).isEqualTo("Explain why this location");
    }
}
