package com.crispyland.web.gatekeeper;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.web.gatekeeper.GatekeeperEvent.EventType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class QuizControllerTest {
    @TempDir Path directory;
    private GatekeeperEventLog log;
    private QuizPool pool;
    private QuizController controller;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private GatekeeperProperties properties(boolean enabled, int remedial, int reinforcement) {
        return new GatekeeperProperties(true, "Adelaide", directory.toString(), "", "", "",
                100, 100, 100, false, enabled, remedial, reinforcement);
    }

    @BeforeEach void setUp() {
        log = new GatekeeperEventLog(directory);
        pool = QuizPool.loadAll();
        controller = new QuizController(log, pool, properties(true, 3, 3));
        request.setRemoteAddr("127.0.0.1");
        session("one");
    }

    private void session(String id) {
        log.append(id, "student-" + id, 0, EventType.SESSION_STARTED, mapper.createObjectNode());
    }

    private QuizController.QuizStartResponse start(String session, String trigger) {
        return (QuizController.QuizStartResponse) controller.startQuiz(
                new QuizController.QuizStartRequest(session, trigger), request).getBody();
    }

    private List<QuizController.AnswerEntry> answers(QuizController.QuizStartResponse quiz, boolean correct) {
        return quiz.questions().stream().map(view -> {
            QuizQuestion q = pool.questionsForGate(quiz.gate()).stream()
                    .filter(candidate -> candidate.id().equals(view.id())).findFirst().orElseThrow();
            String answer = "matching".equals(q.type())
                    ? mapper.writeValueAsString(q.pairs().stream().map(QuizQuestion.Pair::right).toList())
                    : String.valueOf(q.correct());
            return new QuizController.AnswerEntry(q.id(), correct ? answer : "wrong");
        }).toList();
    }

    @Test void reinforcementTargetsPassedGateIncludingFinalGate() {
        for (int gate = 0; gate <= 6; gate++) {
            log.append("one", "student-one", gate, EventType.GATE_UNLOCKED, mapper.createObjectNode());
            QuizController.QuizStartResponse quiz = start("one", "reinforcement");
            assertThat(quiz.gate()).isEqualTo(gate);
            assertThat(quiz.questions()).hasSize(3);
            assertThat(quiz.questions()).allMatch(q -> q.id().startsWith("g" + quiz.gate() + "-"));
        }
    }

    @Test void quizzesSurviveRestartAndCannotBeSubmittedInAnotherSession() {
        log.append("one", "student-one", 0, EventType.GATE_FAILED, mapper.createObjectNode());
        var quiz = start("one", "remedial");
        session("two");
        controller = new QuizController(new GatekeeperEventLog(directory), pool, properties(true, 3, 3));
        assertThat(start("one", "remedial").quizId()).isEqualTo(quiz.quizId());
        assertThat(controller.submitQuiz(new QuizController.QuizSubmitRequest("two", quiz.quizId(),
                answers(quiz, true)), request).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.submitQuiz(new QuizController.QuizSubmitRequest("one", quiz.quizId(),
                answers(quiz, true)), request).getStatusCode().value()).isEqualTo(200);
        assertThat(log.readAll("two")).hasSize(1);
    }

    @Test void completionPreservesAnswersUpdatesSnapshotAndRetriesExactlyOnce() {
        log.append("one", "student-one", 0, EventType.GATE_FAILED, mapper.createObjectNode());
        log.rebuildSnapshot("one");
        var quiz = start("one", "remedial");
        var submission = new QuizController.QuizSubmitRequest("one", quiz.quizId(), answers(quiz, false));
        var first = controller.submitQuiz(submission, request);
        var retry = controller.submitQuiz(submission, request);
        assertThat(first.getBody()).isEqualTo(retry.getBody());
        assertThat(log.readAll("one").stream().filter(e -> e.type() == EventType.QUIZ_COMPLETED)).hasSize(1);
        GatekeeperSession session = new GatekeeperEventLog(directory).loadSession("one");
        assertThat(session.isPendingQuiz()).isFalse();
        assertThat(session.getQuizHistory().get(0).getFirst().answers()).hasSize(3);
        assertThat(session.getQuizHistory().get(0).getFirst().score()).isZero();
        assertThat(session.getLastQuizSummary(0)).contains("Review concepts:");
        assertThat(log.verifyChain("one")).isNull();
        assertThat(controller.startQuiz(new QuizController.QuizStartRequest("one", "remedial"), request)
                .getBody()).isEqualTo(java.util.Map.of("quizAvailable", false));
    }

    @Test void malformedAnswersDoNotConsumeQuiz() {
        log.append("one", "student-one", 0, EventType.GATE_FAILED, mapper.createObjectNode());
        var quiz = start("one", "remedial");
        var valid = answers(quiz, true);
        List<List<QuizController.AnswerEntry>> invalid = new ArrayList<>();
        invalid.add(List.of());
        invalid.add(List.of(valid.getFirst(), valid.getFirst()));
        invalid.add(List.of(new QuizController.AnswerEntry("unknown", "x")));
        List<QuizController.AnswerEntry> nullEntry = new ArrayList<>();
        nullEntry.add(null);
        invalid.add(nullEntry);
        invalid.add(List.of(new QuizController.AnswerEntry(valid.getFirst().questionId(), null)));
        for (var entries : invalid) {
            assertThat(controller.submitQuiz(new QuizController.QuizSubmitRequest("one", quiz.quizId(), entries),
                    request).getStatusCode().value()).isEqualTo(400);
        }
        var result = (QuizController.QuizSubmitResponse) controller.submitQuiz(
                new QuizController.QuizSubmitRequest("one", quiz.quizId(), valid), request).getBody();
        assertThat(result.score()).isEqualTo(result.total());
    }

    @Test void configurationAndTriggersAreEnforced() {
        assertThat(controller.startQuiz(new QuizController.QuizStartRequest("../one", "remedial"), request)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.startQuiz(new QuizController.QuizStartRequest("one", "other"), request)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.startQuiz(new QuizController.QuizStartRequest("one", "remedial"), request)
                .getBody()).isEqualTo(java.util.Map.of("quizAvailable", false));
        log.append("one", "student-one", 0, EventType.GATE_FAILED, mapper.createObjectNode());
        controller = new QuizController(log, pool, properties(false, 2, 1));
        assertThat(controller.startQuiz(new QuizController.QuizStartRequest("one", "remedial"), request)
                .getBody()).isEqualTo(java.util.Map.of("quizAvailable", false));
        controller = new QuizController(log, pool, properties(true, 2, 1));
        assertThat(start("one", "remedial").questions()).hasSize(2);
        log.append("one", "student-one", 0, EventType.GATE_UNLOCKED, mapper.createObjectNode());
        assertThat(start("one", "reinforcement").questions()).hasSize(1);
    }

    @Test void allQuestionTypesGradeCorrectlyAndAnswersStayOutOfPresentation() {
        controller = new QuizController(log, pool, properties(true, 100, 100));
        for (int gate = 0; gate <= 6; gate++) {
            log.append("one", "student-one", gate, EventType.GATE_UNLOCKED, mapper.createObjectNode());
            var quiz = start("one", "reinforcement");
            String json = mapper.writeValueAsString(quiz);
            assertThat(json).doesNotContain("\"correct\":", "\"explanation\":", "\"textbookRef\":");
            var result = (QuizController.QuizSubmitResponse) controller.submitQuiz(
                    new QuizController.QuizSubmitRequest("one", quiz.quizId(), answers(quiz, true)), request).getBody();
            for (var question : quiz.questions()) {
                if (question.pairs() != null) {
                    question.pairs().forEach(pair -> assertThat(pair.rightChoices()).doesNotHaveDuplicates());
                }
            }
            assertThat(result.score()).isEqualTo(pool.questionsForGate(gate).size());
            assertThat(result.results()).allMatch(QuizController.QuestionResult::correct);
        }
    }

    @Test void completingAnOlderQuizDoesNotClearANewerFailureAtTheSameGate() {
        log.append("one", "student-one", 0, EventType.GATE_FAILED, mapper.createObjectNode());
        var quiz = start("one", "remedial");
        log.append("one", "student-one", 0, EventType.GATE_FAILED, mapper.createObjectNode());
        controller.submitQuiz(new QuizController.QuizSubmitRequest("one", quiz.quizId(), answers(quiz, true)), request);
        assertThat(log.loadSession("one").isPendingQuiz()).isTrue();
        assertThat(start("one", "remedial").quizId()).isNotEqualTo(quiz.quizId());
    }

    @Test void completionWithoutPresentationReferenceCannotClearPendingQuiz() {
        log.append("one", "student-one", 0, EventType.GATE_FAILED, mapper.createObjectNode());
        var payload = mapper.createObjectNode();
        payload.put("quizId", "legacy-quiz");
        payload.put("trigger", "remedial");
        payload.put("score", 1);
        payload.put("total", 1);
        log.append("one", "student-one", 0, EventType.QUIZ_COMPLETED, payload);
        var session = log.rebuildSnapshot("one");
        assertThat(session.isPendingQuiz()).isTrue();
        assertThat(session.getQuizHistory().get(0)).hasSize(1);
    }

    @Test void delayedCompletionUsesOriginalGateWithoutClearingLaterRemediation() {
        log.append("one", "student-one", 0, EventType.GATE_UNLOCKED, mapper.createObjectNode());
        var quiz = start("one", "reinforcement");
        log.append("one", "student-one", 1, EventType.GATE_FAILED, mapper.createObjectNode());
        controller.submitQuiz(new QuizController.QuizSubmitRequest("one", quiz.quizId(), answers(quiz, true)), request);
        var session = log.loadSession("one");
        assertThat(session.getQuizHistory()).containsKey(0).doesNotContainKey(1);
        assertThat(session.isPendingQuiz()).isTrue();
    }
}
