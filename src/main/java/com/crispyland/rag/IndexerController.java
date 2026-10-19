package com.crispyland.rag;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/indexer")
public class IndexerController {

    private static final Logger log = LoggerFactory.getLogger(IndexerController.class);

    private final IndexBuilder builder;
    private final IndexStore store;

    public IndexerController(IndexBuilder builder, IndexStore store) {
        this.builder = builder;
        this.store   = store;
    }

    @GetMapping
    public String page(Model model) {
        Optional<IndexFile> fixedOpt      = store.load("fixed");
        Optional<IndexFile> structuredOpt = store.load("structured");
        model.addAttribute("fixed",      fixedOpt.orElse(null));
        model.addAttribute("structured", structuredOpt.orElse(null));
        // sample 3 chunks from each for the preview table
        fixedOpt.ifPresent(f -> model.addAttribute("fixedSamples",
                f.chunks().subList(0, Math.min(3, f.chunks().size()))));
        structuredOpt.ifPresent(s -> model.addAttribute("structuredSamples",
                s.chunks().subList(0, Math.min(3, s.chunks().size()))));
        // per-file breakdown: sourceFile → chunk count, sorted by file name
        structuredOpt.ifPresent(s -> model.addAttribute("structuredFiles", fileBreakdown(s.chunks())));
        fixedOpt.ifPresent(f -> model.addAttribute("fixedFiles", fileBreakdown(f.chunks())));
        return "indexer";
    }

    /** Groups chunks by sourceFile, returns a map sorted alphabetically by file name. */
    private static Map<String, Long> fileBreakdown(List<Chunk> chunks) {
        return chunks.stream()
                .collect(Collectors.groupingBy(Chunk::sourceFile, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    @PostMapping("/reindex")
    public String reindex() {
        new Thread(() -> {
            try {
                log.info("Re-indexing started...");
                builder.buildAndSave("fixed");
                builder.buildAndSave("structured");
                builder.buildSupportFaqIndex();
                builder.buildGatekeeperIndex();
                log.info("Re-indexing complete.");
            } catch (IOException e) {
                log.error("Re-indexing failed: {}", e.getMessage(), e);
            }
        }, "indexer-thread").start();
        return "redirect:/indexer";
    }

    @PostMapping("/reindex-support")
    public String reindexSupport() {
        new Thread(() -> {
            try {
                log.info("Support FAQ re-indexing started...");
                builder.buildSupportFaqIndex();
                log.info("Support FAQ re-indexing complete.");
            } catch (IOException e) {
                log.error("Support FAQ re-indexing failed: {}", e.getMessage(), e);
            }
        }, "indexer-thread").start();
        return "redirect:/indexer";
    }
}
