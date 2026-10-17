package com.crispyland.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the full RAG retrieval pipeline:
 * <ol>
 *   <li>Query rewriting — rephrases the question for better semantic recall (when enabled).</li>
 *   <li>Embedding + cosine search — returns top {@code candidateK} above
 *       {@code similarityThreshold}.</li>
 *   <li>LLM reranking — scores and filters to final {@code finalK} (when enabled).</li>
 * </ol>
 * All intermediate data is captured in {@link RagDebug} for the debug panel.
 */
@Service
public class RagSearcher {

    private static final Logger log = LoggerFactory.getLogger(RagSearcher.class);

    private final EmbeddingPort embedding;
    private final IndexStore store;
    private final QueryRewriter rewriter;
    private final Reranker reranker;

    public RagSearcher(EmbeddingPort embedding, IndexStore store,
                       QueryRewriter rewriter, Reranker reranker) {
        this.embedding = embedding;
        this.store     = store;
        this.rewriter  = rewriter;
        this.reranker  = reranker;
    }

    public RagSearchResult search(String query, List<String> recentContext, RagConfig config) {
        return search(query, recentContext, config, "structured");
    }

    public RagSearchResult search(String query, List<String> recentContext,
                                   RagConfig config, String indexName) {
        long totalStart = System.nanoTime();

        Optional<IndexFile> indexFile = store.load(indexName);
        if (indexFile.isEmpty()) return RagSearchResult.empty(query);

        // 1. Query rewrite
        QueryRewriteResult qr;
        if (config.queryRewriteEnabled()) {
            qr = rewriter.rewrite(query, recentContext);
        } else {
            qr = QueryRewriteResult.unchanged(query);
        }
        String effectiveQuery = qr.rewritten();

        // 2. Embed + cosine search — wider candidate set
        float[] queryVec = embedding.embed(effectiveQuery);

        List<ScoredChunk> allScored = indexFile.get().chunks().stream()
                .filter(c -> c.embedding() != null)
                .map(c -> new ScoredChunk(c, cosine(queryVec, c.embedding())))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(config.candidateK())
                .toList();

        // Split into passed / dropped by similarity threshold
        List<ScoredChunk> stage1Passed = new ArrayList<>();
        List<ScoredChunk> droppedBySimilarity = new ArrayList<>();
        for (ScoredChunk sc : allScored) {
            if (sc.score() >= config.similarityThreshold()) {
                stage1Passed.add(sc);
            } else {
                droppedBySimilarity.add(sc);
            }
        }

        log.debug("RAG stage 1: {} candidates, {} passed similarity threshold {}",
                allScored.size(), stage1Passed.size(), config.similarityThreshold());

        // 3. Rerank or pass through
        RerankerResult rerankerResult;
        List<RankedChunk> afterRerank;
        List<RankedChunk> droppedByRerank = new ArrayList<>();

        if (config.rerankEnabled() && !stage1Passed.isEmpty()) {
            rerankerResult = reranker.rerank(effectiveQuery, stage1Passed, config);

            // Split by rerank score threshold, then take finalK
            List<RankedChunk> passed = new ArrayList<>();
            for (RankedChunk rc : rerankerResult.ranked()) {
                if (rerankerResult.fellBack()) {
                    // Fallback: keep all, they only have cosine scores
                    passed.add(rc);
                } else if (rc.rerankScore() >= config.rerankScoreThreshold()) {
                    passed.add(rc);
                } else {
                    droppedByRerank.add(rc);
                }
            }
            passed.sort(rerankerResult.fellBack()
                    ? Comparator.comparingDouble(RankedChunk::cosineScore).reversed()
                    : Comparator.comparingInt(RankedChunk::rerankScore).reversed());
            afterRerank = passed.stream().limit(config.finalK()).toList();

        } else {
            rerankerResult = RerankerResult.SKIPPED;
            // No reranker: take top finalK from similarity-passed candidates
            afterRerank = stage1Passed.stream()
                    .limit(config.finalK())
                    .map(RankedChunk::fromCosineOnly)
                    .toList();
        }

        long totalLatency = (System.nanoTime() - totalStart) / 1_000_000;

        // Build final ScoredChunk list for prompt injection (uses cosine score for display)
        List<ScoredChunk> finalChunks = afterRerank.stream()
                .map(rc -> new ScoredChunk(rc.chunk(), rc.cosineScore()))
                .toList();

        RagDebug debug = new RagDebug(qr, allScored, droppedBySimilarity,
                rerankerResult, droppedByRerank, afterRerank, totalLatency);

        log.info("RAG pipeline ({}ms): query='{}' effective='{}' stage1={} passed={} final={}",
                totalLatency, query, effectiveQuery, allScored.size(),
                stage1Passed.size(), finalChunks.size());

        return new RagSearchResult(query, effectiveQuery, finalChunks, debug);
    }

    private static float cosine(float[] a, float[] b) {
        float dot = 0, normA = 0, normB = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0f;
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }
}
