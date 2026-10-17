# Rate Limits

## Cerebras API limits

AIAC uses the Cerebras API with a **50,000 token context window limit**. This is an account-level cap, not a per-request limit.

### What counts toward the limit

- The system prompt (memory layers, tool schemas, instructions)
- The conversation history (compressed after every 10 messages)
- RAG context chunks (when RAG is enabled)
- The user's message
- The model's response

### Common errors

#### "Rate limit exceeded. Please try again later."

**Cause:** The Cerebras API returned a 429 status. This can happen when:
- Too many requests in a short period
- The account's token budget is exhausted

**Resolution:**
1. Wait a few minutes and try again
2. If persistent, the API key's quota may be exhausted — check with the admin

#### "Context window limit reached (50000 tokens)"

**Cause:** The combined prompt (system + history + RAG + user message) exceeds 50,000 tokens.

**This is more likely when:**
- Long conversation history has accumulated
- RAG is enabled and returning many chunks
- Working memory has many extracted facts

**Resolution:**
1. Start a new conversation (clears short-term history)
2. Disable RAG if not needed for the current question
3. The agent automatically compresses history every 10 messages, but long conversations will still approach the limit

### How AIAC manages token budget

- **Short-term compression:** Every 10 messages, older messages are compressed into a summary
- **Keep-recent:** The last 6 messages are always kept uncompressed
- **Working memory cap:** Maximum 12 facts extracted per turn
- **Long-term memory cap:** Maximum 24 entries per visitor
- **RAG finalK:** Configurable limit on how many chunks are injected

### Tips for users hitting rate limits frequently

1. Start new conversations for unrelated topics rather than continuing one long thread
2. Disable RAG when asking general questions that don't need codebase context
3. Keep messages concise — shorter prompts leave more room for the response
