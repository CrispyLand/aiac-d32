package com.crispyland.web.gatekeeper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads per-gate quiz question pools from classpath resources and selects
 * questions for remedial or reinforcement quizzes. No LLM calls — selection
 * uses missing criteria to prioritize relevant concepts.
 */
public class QuizPool {

    private static final Logger log = LoggerFactory.getLogger(QuizPool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<Integer, List<QuizQuestion>> pools; // gate → questions

    private QuizPool(Map<Integer, List<QuizQuestion>> pools) {
        this.pools = pools;
    }

    /**
     * Load all gate quiz pools from classpath. Stops at first missing file.
     */
    public static QuizPool loadAll() {
        Map<Integer, List<QuizQuestion>> pools = new LinkedHashMap<>();
        for (int gate = 0; gate <= 6; gate++) {
            String path = "gatekeeper/gate" + gate + "-quiz-pool.json";
            try (InputStream is = QuizPool.class.getClassLoader().getResourceAsStream(path)) {
                if (is == null) {
                    log.debug("No quiz pool found for gate {}, stopping", gate);
                    break;
                }
                JsonNode root = mapper.readTree(is);
                List<QuizQuestion> questions = parseQuestions(root.get("questions"));
                pools.put(gate, questions);
                log.info("Loaded {} quiz questions for gate {}", questions.size(), gate);
            } catch (Exception e) {
                log.warn("Failed to load quiz pool for gate {}: {}", gate, e.getMessage());
            }
        }
        return new QuizPool(pools);
    }

    /**
     * Select questions targeting the missing criteria after a gate failure.
     * Prioritizes questions that cover the missing criteria, with diversity.
     */
    public List<QuizQuestion> selectForFailure(int gate, List<String> missingCriteria, int count) {
        List<QuizQuestion> pool = pools.getOrDefault(gate, List.of());
        if (pool.isEmpty()) return List.of();

        // First: questions matching at least one missing criterion
        List<QuizQuestion> matching = new ArrayList<>();
        List<QuizQuestion> nonMatching = new ArrayList<>();
        for (QuizQuestion q : pool) {
            if (q.criteria().stream().anyMatch(missingCriteria::contains)) {
                matching.add(q);
            } else {
                nonMatching.add(q);
            }
        }

        List<QuizQuestion> selected = new ArrayList<>();

        // Randomize ties, then cover as many missing criteria as possible.
        List<QuizQuestion> remaining = new ArrayList<>(matching);
        Collections.shuffle(remaining, ThreadLocalRandom.current());
        java.util.Set<String> uncovered = new java.util.HashSet<>(missingCriteria);
        while (selected.size() < count && !remaining.isEmpty()) {
            QuizQuestion best = remaining.stream().max(java.util.Comparator.comparingLong(
                    q -> q.criteria().stream().filter(uncovered::contains).count())).orElseThrow();
            selected.add(best);
            uncovered.removeAll(best.criteria());
            remaining.remove(best);
        }

        // Fill up from non-matching if needed
        Collections.shuffle(nonMatching, ThreadLocalRandom.current());
        while (selected.size() < count && !nonMatching.isEmpty()) {
            selected.add(nonMatching.removeFirst());
        }

        return selected;
    }

    /**
     * Select random questions for post-gate reinforcement.
     */
    public List<QuizQuestion> selectForReinforcement(int gate, int count) {
        List<QuizQuestion> pool = pools.getOrDefault(gate, List.of());
        if (pool.isEmpty()) return List.of();

        List<QuizQuestion> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    /** Check if a quiz pool exists for a given gate. */
    public boolean hasPool(int gate) {
        return pools.containsKey(gate) && !pools.get(gate).isEmpty();
    }

    /** Get all questions for a gate (for testing). */
    List<QuizQuestion> questionsForGate(int gate) {
        return pools.getOrDefault(gate, List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<QuizQuestion> parseQuestions(JsonNode questionsNode) {
        List<QuizQuestion> questions = new ArrayList<>();
        if (questionsNode == null || !questionsNode.isArray()) return questions;

        for (JsonNode qNode : questionsNode) {
            String id = qNode.get("id").asText();
            String type = qNode.get("type").asText();
            List<String> criteria = mapper.convertValue(qNode.get("criteria"),
                    new TypeReference<List<String>>() {});
            String concept = qNode.has("concept") ? qNode.get("concept").asText() : "";
            String stem = qNode.get("stem").asText();
            String explanation = qNode.has("explanation") ? qNode.get("explanation").asText() : "";

            List<QuizQuestion.Option> options = List.of();
            if (qNode.has("options") && qNode.get("options").isArray()) {
                options = new ArrayList<>();
                for (JsonNode opt : qNode.get("options")) {
                    options.add(new QuizQuestion.Option(opt.get("key").asText(), opt.get("text").asText()));
                }
            }

            Object correct = null;
            if (qNode.has("correct") && !qNode.get("correct").isNull()) {
                JsonNode correctNode = qNode.get("correct");
                if (correctNode.isBoolean()) {
                    correct = correctNode.booleanValue();
                } else {
                    correct = correctNode.asText();
                }
            }

            List<QuizQuestion.Pair> pairs = List.of();
            if (qNode.has("pairs") && qNode.get("pairs").isArray()) {
                pairs = new ArrayList<>();
                for (JsonNode pair : qNode.get("pairs")) {
                    pairs.add(new QuizQuestion.Pair(pair.get("left").asText(), pair.get("right").asText()));
                }
            }

            String textbookRef = qNode.has("textbookRef") ? qNode.get("textbookRef").asText() : "";

            questions.add(new QuizQuestion(id, type, criteria, concept, stem, options, correct, explanation, pairs, textbookRef));
        }
        return questions;
    }
}
