package com.crispyland.rag;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(IndexBuilder.class);
    private static final int EMBED_BATCH = 50;

    private final DocumentLoader loader;
    private final FixedSizeChunker fixed;
    private final StructuredChunker structured;
    private final EmbeddingPort embedding;
    private final IndexStore store;
    private final RagProperties props;

    public IndexBuilder(DocumentLoader loader, FixedSizeChunker fixed,
                        StructuredChunker structured, EmbeddingPort embedding,
                        IndexStore store, RagProperties props) {
        this.loader     = loader;
        this.fixed      = fixed;
        this.structured = structured;
        this.embedding  = embedding;
        this.store      = store;
        this.props      = props;
    }

    public IndexFile buildAndSave(String strategy) throws IOException {
        List<LoadedDocument> docs = loader.loadAll();

        List<Chunk> chunks = new ArrayList<>();
        for (LoadedDocument doc : docs) {
            List<Chunk> docChunks = "fixed".equals(strategy)
                    ? fixed.chunk(doc, props.chunkSize(), props.overlap())
                    : structured.chunk(doc);
            chunks.addAll(docChunks);
        }
        log.info("IndexBuilder [{}]: {} chunks from {} docs before embedding",
                strategy, chunks.size(), docs.size());

        chunks = embedAll(chunks);

        IndexMetadata meta = buildMetadata(strategy, chunks);
        IndexFile file = new IndexFile(meta, chunks);
        store.save(file, strategy);
        return file;
    }

    /**
     * Builds a separate RAG index from the support FAQ docs (files under docs/support/).
     */
    public IndexFile buildSupportFaqIndex() throws IOException {
        List<LoadedDocument> allDocs = loader.loadAll();
        List<LoadedDocument> supportDocs = allDocs.stream()
                .filter(d -> d.sourceFile().contains("docs/support/"))
                .toList();
        log.info("IndexBuilder [support-faq]: found {} support docs out of {} total",
                supportDocs.size(), allDocs.size());

        List<Chunk> chunks = new ArrayList<>();
        for (LoadedDocument doc : supportDocs) {
            chunks.addAll(structured.chunk(doc));
        }
        log.info("IndexBuilder [support-faq]: {} chunks before embedding", chunks.size());

        chunks = embedAll(chunks);

        IndexMetadata meta = buildMetadata("support-faq", chunks);
        IndexFile file = new IndexFile(meta, chunks);
        store.save(file, "support-faq");
        return file;
    }

    /**
     * Builds a separate RAG index from the gatekeeper docs (files under docs/gatekeeper-rag/).
     */
    public IndexFile buildGatekeeperIndex() throws IOException {
        List<LoadedDocument> allDocs = loader.loadAll();
        List<LoadedDocument> gkDocs = allDocs.stream()
                .filter(d -> d.sourceFile().contains("docs/gatekeeper-rag/"))
                .toList();
        log.info("IndexBuilder [gatekeeper]: found {} gatekeeper docs out of {} total",
                gkDocs.size(), allDocs.size());

        List<Chunk> chunks = new ArrayList<>();
        for (LoadedDocument doc : gkDocs) {
            chunks.addAll(structured.chunk(doc));
        }
        log.info("IndexBuilder [gatekeeper]: {} chunks before embedding", chunks.size());

        chunks = embedAll(chunks);

        IndexMetadata meta = buildMetadata("gatekeeper", chunks);
        IndexFile file = new IndexFile(meta, chunks);
        store.save(file, "gatekeeper");
        return file;
    }

    /**
     * Chunks, embeds, and merges a single document into the existing structured index.
     * Existing chunks from the same sourceFile are replaced, not duplicated.
     */
    public void mergeDocument(LoadedDocument doc) throws IOException {
        List<Chunk> newChunks = structured.chunk(doc);
        newChunks = embedAll(newChunks);

        // Load existing, remove stale chunks for this source, append new
        Optional<IndexFile> existing = store.load("structured");
        List<Chunk> merged = new ArrayList<>();
        if (existing.isPresent()) {
            existing.get().chunks().stream()
                .filter(c -> !c.sourceFile().equals(doc.sourceFile()))
                .forEach(merged::add);
        }
        merged.addAll(newChunks);

        IndexMetadata meta = buildMetadata("structured", merged);
        store.save(new IndexFile(meta, merged), "structured");
        log.info("IndexBuilder.mergeDocument: merged {} new chunks from {}", newChunks.size(), doc.sourceFile());
    }

    private List<Chunk> embedAll(List<Chunk> chunks) {
        if (!props.embedding().enabled()) {
            log.info("Embedding disabled — skipping.");
            return chunks;
        }
        List<Chunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH) {
            int end = Math.min(i + EMBED_BATCH, chunks.size());
            List<Chunk> batch = chunks.subList(i, end);
            List<String> texts = batch.stream().map(Chunk::text).toList();
            float[][] vectors = embedding.embedAll(texts);
            for (int j = 0; j < batch.size(); j++) {
                result.add(batch.get(j).withEmbedding(vectors[j]));
            }
            log.debug("Embedded batch {}/{}", end, chunks.size());
        }
        log.info("Embedding complete: {} chunks", result.size());
        return result;
    }

    private IndexMetadata buildMetadata(String strategy, List<Chunk> chunks) {
        int total = chunks.size();
        if (total == 0) {
            return new IndexMetadata(strategy, embedding.modelName(),
                    embedding.dimension(), Instant.now().toString(), 0, 0, 0, 0);
        }
        int sum = 0, min = Integer.MAX_VALUE, max = 0;
        for (Chunk c : chunks) {
            sum += c.charLength();
            if (c.charLength() < min) min = c.charLength();
            if (c.charLength() > max) max = c.charLength();
        }
        return new IndexMetadata(strategy, embedding.modelName(), embedding.dimension(),
                Instant.now().toString(), total, sum / total, min, max);
    }
}
