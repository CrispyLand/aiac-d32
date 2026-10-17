# Local Models (Ollama)

## How local model support works

AIAC has experimental support for local models via Ollama. When configured, the chat can route requests to a locally running Ollama instance instead of the Cerebras API.

## Current status

Local model support requires Ollama to be running **on the same machine as the agent server**, not on the user's local machine. The agent connects to Ollama at `localhost:11434`.

## Common issues

### "Ollama is not available" error

**Symptom:** The user toggles the Ollama/local model option and sees "Ollama is not available on the server."

**Cause:** Ollama is not running on the server, or it's running on a different machine.

**Important distinction:** Ollama must be installed and running on the **server** where AIAC is deployed, not on the user's personal computer. The agent makes server-side calls to Ollama.

**Resolution for server admins:**
1. Install Ollama on the server: `curl -fsSL https://ollama.com/install.sh | sh`
2. Pull a model: `ollama pull llama3.1`
3. Ensure Ollama is running: `systemctl status ollama` or `ollama serve`
4. Restart the AIAC agent

### Performance considerations

- Local models are significantly slower than Cerebras for most tasks
- They do not have the 50,000 token context window limit (varies by model)
- Quality of responses depends on the model chosen
- Tool calling support varies by model — some local models cannot use MCP tools

### When to recommend local models

- When the user wants to avoid sending data to external APIs
- When Cerebras is experiencing downtime or rate limiting
- For experimentation and testing
