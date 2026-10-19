package com.crispyland.web.gatekeeper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class JudgeResultTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void deserializesJudgeResponse() throws Exception {
        String json = """
                {
                  "gate": 0,
                  "criteria": {
                    "C1": {"met": true, "evidence": "a coffee shop"},
                    "C2": {"met": true, "evidence": "in Adelaide"},
                    "C3": {"met": false, "evidence": ""},
                    "C4": {"met": true, "evidence": "serving locals"},
                    "C5": {"met": true, "evidence": "genuine interest"}
                  },
                  "met": false,
                  "missing": ["C3"],
                  "next_focus": "Ask about customer need",
                  "teacher_note": "Student engaged well",
                  "integrity_flags": []
                }
                """;

        JudgeResult result = mapper.readValue(json, JudgeResult.class);
        assertThat(result.gate()).isEqualTo(0);
        assertThat(result.met()).isFalse();
        assertThat(result.missing()).containsExactly("C3");
        assertThat(result.criteria()).hasSize(5);
        assertThat(result.criteria().get("C1").met()).isTrue();
        assertThat(result.criteria().get("C3").met()).isFalse();
        assertThat(result.nextFocus()).isEqualTo("Ask about customer need");
    }

    @Test
    void allCriteriaMetWhenTrue() throws Exception {
        String json = """
                {
                  "gate": 0,
                  "criteria": {
                    "C1": {"met": true, "evidence": "a"},
                    "C2": {"met": true, "evidence": "b"}
                  },
                  "met": true,
                  "missing": [],
                  "next_focus": "",
                  "teacher_note": "",
                  "integrity_flags": []
                }
                """;

        JudgeResult result = mapper.readValue(json, JudgeResult.class);
        boolean allMet = result.criteria().values().stream()
                .allMatch(JudgeResult.CriterionResult::met);
        assertThat(allMet).isTrue();
    }
}
