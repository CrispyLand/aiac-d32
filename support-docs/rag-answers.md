# RAG (Retrieval-Augmented Generation) Answers

## How RAG works in AIAC

When the RAG toggle is enabled in the chat UI, the agent searches a pre-built index of the project's source code and documentation before answering. The pipeline:

1. **Query rewrite** — rephrases the question for better semantic recall (when enabled)
2. **Embedding + cosine search** — finds the top candidates above a similarity threshold
3. **LLM reranking** — scores and filters to the final set (when enabled)
4. **Context injection** — the retrieved chunks are added to the LLM prompt

The index covers all source files (agent, MCP server, weather server) and project documentation (README.md, CLAUDE.md).

## Common issues

### Agent says "I don't know" or "insufficient context"

**Symptom:** With RAG enabled, the agent cannot answer questions about the codebase.

**Possible causes:**

1. **Index not built:** The RAG index must be built before it can be searched. Visit `/indexer` and click "Reindex" to build both fixed and structured indexes.

2. **Embedding service unavailable:** If the OpenAI embedding API is down or the key is missing, chunks have no vectors and cosine search returns nothing. Check `spring.ai.openai.api-key` in the configuration.

3. **Similarity threshold too high:** The default threshold filters out chunks with low cosine similarity. If the question is phrased very differently from the source code, no chunks may pass.

4. **Question too vague:** Broad questions like "how does it work" match too many things weakly. Specific questions like "how does CalendarReader query events" get better results.

### RAG citations show numbers but no file names

**Symptom:** The sources section shows [1], [2] etc. but actual file names are blank.

**Cause:** This is a known display bug in the chat UI. The citation data includes the source file path, but the rendering template does not display it correctly.

**Status:** A fix is being prepared. The underlying data is correct — only the display is affected.

### How to reindex

1. Navigate to `/indexer` in the browser
2. Click "Reindex" — this rebuilds both the fixed-size and structured indexes
3. Wait for the background thread to complete (check server logs)
4. The index is stored as `chunks-fixed.json` and `chunks-structured.json`

### RAG not using uploaded documents

The current RAG implementation indexes source code and project docs at build time. There is no runtime document upload feature. To add new documents to the index:
1. Place the files in the appropriate source directory
2. Rebuild the project
3. Reindex via `/indexer`
