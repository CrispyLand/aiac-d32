package com.crispyland.web.gatekeeper;

import java.util.List;

/**
 * Verifies that judge evidence quotes actually appear in the student's text.
 * Normalises whitespace, quote characters, and punctuation before matching.
 * Three match levels: exact (after normalisation), fuzzy (≥0.8 similarity), rejected.
 */
public class EvidenceVerifier {

    public enum MatchLevel { EXACT, FUZZY, REJECTED }

    public record VerifyResult(MatchLevel level, String criterionKey) {}

    /**
     * Verify a single evidence quote against the submitted answer and earlier student messages.
     */
    public static MatchLevel verify(String evidence, String submittedAnswer,
                                     List<String> earlierStudentMessages) {
        if (evidence == null || evidence.isBlank()) {
            return MatchLevel.EXACT; // Empty evidence is always accepted
        }

        String normEvidence = normalise(evidence);
        if (normEvidence.isBlank()) {
            return MatchLevel.EXACT;
        }

        // Check submitted answer first
        String normAnswer = normalise(submittedAnswer);
        MatchLevel answerMatch = matchAgainst(normEvidence, normAnswer);
        if (answerMatch == MatchLevel.EXACT) return MatchLevel.EXACT;

        // Check earlier student messages
        MatchLevel bestMatch = answerMatch;
        for (String msg : earlierStudentMessages) {
            MatchLevel msgMatch = matchAgainst(normEvidence, normalise(msg));
            if (msgMatch == MatchLevel.EXACT) return MatchLevel.EXACT;
            if (msgMatch.ordinal() < bestMatch.ordinal()) {
                bestMatch = msgMatch;
            }
        }

        return bestMatch;
    }

    /**
     * Verify all non-empty evidence quotes from a judge result.
     * Returns a list of results for criteria with non-empty evidence that weren't exact matches.
     */
    public static List<VerifyResult> verifyAll(JudgeResult result, String submittedAnswer,
                                                List<String> earlierStudentMessages) {
        return result.criteria().entrySet().stream()
                .filter(e -> e.getValue().evidence() != null && !e.getValue().evidence().isBlank())
                .map(e -> new VerifyResult(
                        verify(e.getValue().evidence(), submittedAnswer, earlierStudentMessages),
                        e.getKey()))
                .filter(r -> r.level() != MatchLevel.EXACT)
                .toList();
    }

    // ── Normalisation ───────────────────────────────────────────────────

    static String normalise(String text) {
        if (text == null) return "";
        String s = text;
        // Normalise quote characters: curly quotes → straight, backticks → quotes
        s = s.replace('\u2018', '\'').replace('\u2019', '\'');
        s = s.replace('\u201C', '"').replace('\u201D', '"');
        s = s.replace('`', '\'');
        // Collapse whitespace
        s = s.replaceAll("\\s+", " ").trim();
        // Strip leading/trailing punctuation
        s = s.replaceAll("^[\\p{Punct}\\s]+", "");
        s = s.replaceAll("[\\p{Punct}\\s]+$", "");
        // Lowercase for comparison
        s = s.toLowerCase();
        return s;
    }

    private static MatchLevel matchAgainst(String normEvidence, String normText) {
        if (normText.contains(normEvidence)) {
            return MatchLevel.EXACT;
        }
        // Fuzzy match: check if any substring of similar length has high similarity
        if (fuzzyContains(normEvidence, normText, 0.6)) {
            return MatchLevel.FUZZY;
        }
        return MatchLevel.REJECTED;
    }

    /**
     * Check if any substring of {@code text} with similar length to {@code query}
     * has a Levenshtein similarity ratio ≥ the threshold.
     */
    static boolean fuzzyContains(String query, String text, double threshold) {
        if (query.isEmpty() || text.isEmpty()) return false;
        int qLen = query.length();
        // Slide a window of ±30% of query length
        int minWindow = Math.max(1, (int) (qLen * 0.7));
        int maxWindow = Math.min(text.length(), (int) (qLen * 1.3));

        for (int winLen = minWindow; winLen <= maxWindow; winLen++) {
            for (int start = 0; start + winLen <= text.length(); start++) {
                String sub = text.substring(start, start + winLen);
                double similarity = levenshteinSimilarity(query, sub);
                if (similarity >= threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    static double levenshteinSimilarity(String a, String b) {
        int distance = levenshteinDistance(a, b);
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - ((double) distance / maxLen);
    }

    static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
