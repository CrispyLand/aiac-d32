package com.crispyland.web.gatekeeper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gate definition loaded from classpath resources.
 * Each gate has: tutor block, opening message, judge system prompt, judge user template,
 * max words, and a per-gate JSON schema for the judge responseSchema.
 * Adding Gates 2–6 requires only new resource files, no Java changes.
 */
public record GateDefinition(
        int gate,
        String tutorBlock,
        String openingMessage,
        String judgeSystem,
        String judgeUserTemplate,
        int maxWords,
        String responseSchema) {

    private static final Logger log = LoggerFactory.getLogger(GateDefinition.class);

    /** Load all gate definitions from classpath resources. */
    public static List<GateDefinition> loadAll() {
        List<GateDefinition> gates = new ArrayList<>();
        int[] maxWordsPerGate = {80, 150, 50, 150, 150, 150, 150}; // Gates 0-6

        for (int i = 0; i <= 6; i++) {
            String block = loadResource("gatekeeper/gate" + i + "-block.txt");
            if (block == null) {
                break; // Stop at the first gate that has no block file
            }
            String opening = loadResource("gatekeeper/gate" + i + "-opening.txt");
            String judgeSystem = loadResource("gatekeeper/gate" + i + "-judge-system.txt");
            String judgeUser = loadResource("gatekeeper/gate" + i + "-judge-user.txt");
            String schema = loadResource("gatekeeper/gate" + i + "-judge-schema.json");

            gates.add(new GateDefinition(
                    i,
                    block,
                    opening != null ? opening : "",
                    judgeSystem != null ? judgeSystem : "",
                    judgeUser != null ? judgeUser : "",
                    maxWordsPerGate[i],
                    schema != null ? schema : ""));
            log.info("Loaded gate {} definition", i);
        }
        return List.copyOf(gates);
    }

    /** Load the shared tutor system prompt template. */
    public static String loadTutorSystem() {
        String text = loadResource("gatekeeper/tutor-system.txt");
        if (text == null) {
            throw new IllegalStateException("Missing gatekeeper/tutor-system.txt on classpath");
        }
        return text;
    }

    /** Load the resume prompt template. */
    public static String loadResumePrompt() {
        String text = loadResource("gatekeeper/resume-prompt.txt");
        return text != null ? text : "Welcome back! Let's continue where you left off.";
    }

    static String loadResource(String path) {
        try (InputStream is = GateDefinition.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load resource {}: {}", path, e.getMessage());
            return null;
        }
    }
}
