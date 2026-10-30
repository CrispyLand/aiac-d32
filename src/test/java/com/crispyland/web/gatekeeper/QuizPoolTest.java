package com.crispyland.web.gatekeeper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuizPoolTest {
    private final QuizPool pool = QuizPool.loadAll();

    @Test void allSevenPoolsHaveValidUniqueQuestionsAndAnswers() {
        var ids = new HashSet<String>();
        for (int gate = 0; gate <= 6; gate++) {
            assertThat(pool.hasPool(gate)).isTrue();
            assertThat(pool.questionsForGate(gate)).hasSizeGreaterThanOrEqualTo(3);
            for (var q : pool.questionsForGate(gate)) {
                assertThat(ids.add(q.id())).as(q.id()).isTrue();
                assertThat(q.stem()).isNotBlank();
                assertThat(q.explanation()).isNotBlank();
                assertThat(q.textbookRef()).isNotBlank();
                assertThat(q.criteria()).isNotEmpty();
                assertThat(q.type()).isIn("multiple_choice", "true_false", "matching");
                switch (q.type()) {
                    case "multiple_choice" -> {
                        assertThat(q.options()).hasSizeGreaterThanOrEqualTo(2);
                        assertThat(q.options().stream().map(QuizQuestion.Option::key)).doesNotHaveDuplicates();
                        assertThat(q.options().stream().map(QuizQuestion.Option::key)).contains(String.valueOf(q.correct()));
                    }
                    case "true_false" -> assertThat(q.correct()).isInstanceOf(Boolean.class);
                    case "matching" -> {
                        assertThat(q.pairs()).hasSizeGreaterThanOrEqualTo(2);
                        assertThat(q.pairs().stream().map(QuizQuestion.Pair::left)).doesNotHaveDuplicates();
                        assertThat(q.pairs().stream().map(QuizQuestion.Pair::right)).allMatch(value -> !value.isBlank());
                    }
                }
            }
        }
        assertThat(pool.hasPool(7)).isFalse();
    }

    @Test void remedialSelectionPrioritizesMissingCriteriaWithoutDuplicates() {
        for (int gate = 0; gate <= 6; gate++) {
            var selected = pool.selectForFailure(gate, List.of("C1"), 1);
            assertThat(selected).hasSize(1);
            assertThat(selected.getFirst().criteria()).contains("C1");
            assertThat(pool.selectForReinforcement(gate, 100)).hasSize(pool.questionsForGate(gate).size()).doesNotHaveDuplicates();
            assertThat(pool.selectForFailure(gate, List.of(), 100)).hasSize(pool.questionsForGate(gate).size()).doesNotHaveDuplicates();
        }
        assertThat(pool.selectForFailure(7, List.of("C1"), 3)).isEmpty();
    }
}
