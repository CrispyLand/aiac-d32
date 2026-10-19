package com.crispyland.web.gatekeeper;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.web.gatekeeper.EvidenceVerifier.MatchLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceVerifierTest {

    @Test
    void exactMatchAfterNormalisation() {
        MatchLevel result = EvidenceVerifier.verify(
                "I want to open a cafe",
                "So, I want to open a cafe in Adelaide.",
                List.of());
        assertThat(result).isEqualTo(MatchLevel.EXACT);
    }

    @Test
    void curlyQuotesNormalised() {
        MatchLevel result = EvidenceVerifier.verify(
                "\u201Cmy coffee shop\u201D",
                "I said \"my coffee shop\" would be great",
                List.of());
        assertThat(result).isEqualTo(MatchLevel.EXACT);
    }

    @Test
    void whitespaceCollapsed() {
        MatchLevel result = EvidenceVerifier.verify(
                "a   cafe   in   town",
                "I want a cafe in town",
                List.of());
        assertThat(result).isEqualTo(MatchLevel.EXACT);
    }

    @Test
    void fuzzyMatchForSmallDifferences() {
        // "coffe shop" vs "coffee shop" — one char difference
        MatchLevel result = EvidenceVerifier.verify(
                "coffe shop in the city",
                "I want a coffee shop in the city centre",
                List.of());
        assertThat(result).isIn(MatchLevel.EXACT, MatchLevel.FUZZY);
    }

    @Test
    void rejectedWhenNoMatch() {
        MatchLevel result = EvidenceVerifier.verify(
                "this text does not appear anywhere",
                "Completely different content about farming",
                List.of());
        assertThat(result).isEqualTo(MatchLevel.REJECTED);
    }

    @Test
    void emptyEvidenceIsAccepted() {
        assertThat(EvidenceVerifier.verify("", "any text", List.of()))
                .isEqualTo(MatchLevel.EXACT);
        assertThat(EvidenceVerifier.verify(null, "any text", List.of()))
                .isEqualTo(MatchLevel.EXACT);
        assertThat(EvidenceVerifier.verify("   ", "any text", List.of()))
                .isEqualTo(MatchLevel.EXACT);
    }

    @Test
    void matchFoundInEarlierStudentMessages() {
        MatchLevel result = EvidenceVerifier.verify(
                "I want a bakery",
                "My final answer is about bread",
                List.of("Earlier I said I want a bakery in the suburbs"));
        assertThat(result).isEqualTo(MatchLevel.EXACT);
    }

    @Test
    void levenshteinSimilarityCalculation() {
        assertThat(EvidenceVerifier.levenshteinSimilarity("abc", "abc")).isEqualTo(1.0);
        assertThat(EvidenceVerifier.levenshteinSimilarity("abc", "abd")).isGreaterThan(0.6);
        assertThat(EvidenceVerifier.levenshteinSimilarity("abc", "xyz")).isLessThan(0.5);
    }

    @Test
    void normaliseStripsLeadingTrailingPunctuation() {
        assertThat(EvidenceVerifier.normalise("...hello!")).isEqualTo("hello");
        assertThat(EvidenceVerifier.normalise("\"quoted\"")).isEqualTo("quoted");
    }
}
