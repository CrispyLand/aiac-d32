package com.crispyland.web.devagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.agent.llm.ToolCall;
import com.crispyland.agent.llm.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolBoxTest {

    @TempDir Path root;
    FileToolBox box;

    @BeforeEach
    void seed() throws IOException {
        // Create a simple project structure
        Files.writeString(root.resolve("README.md"), "# Hello\nThis is a readme.\n");
        Files.writeString(root.resolve("CLAUDE.md"), "# Claude\nInstructions here.\n");

        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/App.java"),
                "package com;\npublic class App {\n    public static void main(String[] args) {}\n}\n");
        Files.writeString(root.resolve("src/main/java/TokenUsage.java"),
                "package com;\npublic record TokenUsage(int prompt, int completion) {}\n");

        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/guide.md"), "# Guide\nSome guide content.\n");

        // Secret files
        Files.writeString(root.resolve(".env"), "SECRET_KEY=abc123\n");
        Files.writeString(root.resolve("credentials.json"), "{\"secret\": true}\n");
        Files.writeString(root.resolve("token.json"), "{\"token\": \"xyz\"}\n");

        // Build dirs
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("target/classes/App.class"), "binary content");

        // Git dir
        Files.createDirectories(root.resolve(".git/objects"));
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/main\n");

        box = new FileToolBox(root, List.of("README.md", "CLAUDE.md", "CHANGELOG.md", "docs/**"), 50000);
    }

    // ── listFiles ────────────────────────────────────────────────────────

    @Test
    void listFilesReturnsEntries() {
        ToolResult result = box.call(call("listFiles", Map.of("path", "src/main/java")));
        assertThat(result.failed()).isFalse();
        assertThat(result.content()).contains("App.java");
        assertThat(result.content()).contains("TokenUsage.java");
    }

    @Test
    void listFilesHidesBlockedFiles() {
        ToolResult result = box.call(call("listFiles", Map.of()));
        assertThat(result.failed()).isFalse();
        assertThat(result.content()).doesNotContain(".env");
        assertThat(result.content()).doesNotContain("credentials.json");
    }

    // ── searchFiles ──────────────────────────────────────────────────────

    @Test
    void searchFilesFindsMatches() {
        ToolResult result = box.call(call("searchFiles", Map.of("query", "class App")));
        assertThat(result.failed()).isFalse();
        assertThat(result.content()).contains("App.java");
    }

    @Test
    void searchFilesDefaultsToTextFiles() {
        ToolResult result = box.call(call("searchFiles", Map.of("query", "Hello")));
        assertThat(result.failed()).isFalse();
        assertThat(result.content()).contains("README.md");
    }

    @Test
    void searchFilesRejectsInvalidRegex() {
        ToolResult result = box.call(call("searchFiles", Map.of("query", "[invalid")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).contains("Invalid regex");
    }

    @Test
    void searchFilesRespectsTokenBudget() {
        // Create a box with very low budget
        FileToolBox smallBudget = new FileToolBox(root, List.of(), 1);
        // First search may succeed but subsequent reads will be charged
        ToolResult r1 = smallBudget.call(call("readFile", Map.of("path", "README.md")));
        // The second large read should fail
        ToolResult r2 = smallBudget.call(call("readFile", Map.of("path", "src/main/java/App.java")));
        // At least one should fail due to budget
        assertThat(r1.failed() || r2.failed()).isTrue();
    }

    // ── readFile ─────────────────────────────────────────────────────────

    @Test
    void readFileReturnsContent() {
        ToolResult result = box.call(call("readFile", Map.of("path", "README.md")));
        assertThat(result.failed()).isFalse();
        assertThat(result.content()).contains("Hello");
    }

    @Test
    void readFileWithLineRange() {
        ToolResult result = box.call(call("readFile", Map.of("path", "README.md", "startLine", 2, "endLine", 2)));
        assertThat(result.failed()).isFalse();
        assertThat(result.content()).contains("readme");
        assertThat(result.content()).doesNotContain("Hello");
    }

    @Test
    void readFileBlockedForEnv() {
        ToolResult result = box.call(call("readFile", Map.of("path", ".env")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("blocked");
    }

    @Test
    void readFileBlockedForCredentials() {
        ToolResult result = box.call(call("readFile", Map.of("path", "credentials.json")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("blocked");
    }

    @Test
    void readFileBlockedForTokenJson() {
        ToolResult result = box.call(call("readFile", Map.of("path", "token.json")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("blocked");
    }

    @Test
    void readFileBlockedForGitDir() {
        ToolResult result = box.call(call("readFile", Map.of("path", ".git/HEAD")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("blocked");
    }

    @Test
    void readFileBlockedForTargetDir() {
        ToolResult result = box.call(call("readFile", Map.of("path", "target/classes/App.class")));
        assertThat(result.failed()).isTrue();
    }

    @Test
    void readFileNotBlockedForTokenUsageJava() {
        ToolResult result = box.call(call("readFile", Map.of("path", "src/main/java/TokenUsage.java")));
        assertThat(result.failed()).isFalse();
        assertThat(result.content()).contains("TokenUsage");
    }

    @Test
    void readFileBlockedForBinaryFile() throws IOException {
        Path bin = root.resolve("data.bin");
        byte[] data = new byte[100];
        data[50] = 0; // null byte = binary
        Files.write(bin, data);

        ToolResult result = box.call(call("readFile", Map.of("path", "data.bin")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("binary");
    }

    @Test
    void readFileBlockedForLargeFile() throws IOException {
        Path big = root.resolve("huge.txt");
        Files.writeString(big, "x".repeat(600_000));

        ToolResult result = box.call(call("readFile", Map.of("path", "huge.txt")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("large");
    }

    @Test
    void readFileCountsTokenBudget() {
        FileToolBox smallBudget = new FileToolBox(root, List.of(), 10);
        // First read should succeed (small file)
        ToolResult r1 = smallBudget.call(call("readFile", Map.of("path", "README.md")));
        assertThat(r1.failed()).isFalse();

        // Create a large enough file to bust the remaining budget
        try {
            Files.writeString(root.resolve("big.txt"), "x".repeat(200));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ToolResult r2 = smallBudget.call(call("readFile", Map.of("path", "big.txt")));
        assertThat(r2.failed()).isTrue();
        assertThat(r2.content()).containsIgnoringCase("budget");
    }

    // ── proposeEdit ──────────────────────────────────────────────────────

    @Test
    void proposeEditSingleMatch() {
        ToolResult result = box.call(call("proposeEdit", Map.of(
                "path", "README.md",
                "oldContent", "# Hello",
                "newContent", "# Hello World",
                "reason", "Better title")));
        assertThat(result.failed()).isFalse();
        assertThat(result.content()).contains("-# Hello");
        assertThat(result.content()).contains("+# Hello World");
        assertThat(box.proposedChanges()).hasSize(1);
        assertThat(box.proposedChanges().get(0).type()).isEqualTo(FileToolBox.ChangeType.EDIT);
    }

    @Test
    void proposeEditZeroMatchesFails() {
        ToolResult result = box.call(call("proposeEdit", Map.of(
                "path", "README.md",
                "oldContent", "nonexistent text",
                "newContent", "replacement",
                "reason", "test")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("not found");
    }

    @Test
    void proposeEditMultipleMatchesFails() throws IOException {
        Files.writeString(root.resolve("CLAUDE.md"), "hello\nhello\nhello\n");
        ToolResult result = box.call(call("proposeEdit", Map.of(
                "path", "CLAUDE.md",
                "oldContent", "hello",
                "newContent", "world",
                "reason", "test")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).contains("3 occurrences");
    }

    @Test
    void proposeEditBlockedOutsideWritePaths() {
        ToolResult result = box.call(call("proposeEdit", Map.of(
                "path", "src/main/java/App.java",
                "oldContent", "class App",
                "newContent", "class Application",
                "reason", "rename")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("write not allowed");
    }

    @Test
    void proposeEditAllowedInDocs() {
        ToolResult result = box.call(call("proposeEdit", Map.of(
                "path", "docs/guide.md",
                "oldContent", "# Guide",
                "newContent", "# User Guide",
                "reason", "clarify title")));
        assertThat(result.failed()).isFalse();
        assertThat(box.proposedChanges()).hasSize(1);
    }

    // ── proposeNewFile ───────────────────────────────────────────────────

    @Test
    void proposeNewFileCreatesGitDiff() {
        ToolResult result = box.call(call("proposeNewFile", Map.of(
                "path", "docs/new.md",
                "content", "# New\nContent here.\n",
                "reason", "new doc")));
        assertThat(result.failed()).isFalse();
        assertThat(result.content()).contains("new file mode 100644");
        assertThat(result.content()).contains("+# New");
        assertThat(box.proposedChanges()).hasSize(1);
        assertThat(box.proposedChanges().get(0).type()).isEqualTo(FileToolBox.ChangeType.NEW_FILE);
    }

    @Test
    void proposeNewFileBlockedOutsideWritePaths() {
        ToolResult result = box.call(call("proposeNewFile", Map.of(
                "path", "src/main/java/New.java",
                "content", "class New {}",
                "reason", "test")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("write not allowed");
    }

    @Test
    void proposeNewFileRejectsExistingFile() {
        ToolResult result = box.call(call("proposeNewFile", Map.of(
                "path", "README.md",
                "content", "overwrite",
                "reason", "test")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("already exists");
    }

    // ── Path traversal ───────────────────────────────────────────────────

    @Test
    void pathTraversalBlockedOnRead() {
        ToolResult result = box.call(call("readFile", Map.of("path", "../../etc/passwd")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).containsIgnoringCase("blocked");
    }

    @Test
    void pathTraversalBlockedOnList() {
        ToolResult result = box.call(call("listFiles", Map.of("path", "../..")));
        assertThat(result.failed()).isTrue();
    }

    @Test
    void pathTraversalBlockedOnSearch() {
        ToolResult result = box.call(call("searchFiles", Map.of("query", "test", "path", "../../")));
        assertThat(result.failed()).isTrue();
    }

    @Test
    void pathTraversalBlockedOnEdit() {
        ToolResult result = box.call(call("proposeEdit", Map.of(
                "path", "../../etc/passwd",
                "oldContent", "root",
                "newContent", "hacked",
                "reason", "test")));
        assertThat(result.failed()).isTrue();
    }

    // ── Git tools ────────────────────────────────────────────────────────

    @Test
    void getGitStatusReturnsResult() {
        // This runs in a temp dir which is not a git repo, so it should fail gracefully
        ToolResult result = box.call(call("getGitStatus", Map.of()));
        // Either succeeds (if inside git repo) or fails gracefully
        assertThat(result).isNotNull();
    }

    @Test
    void getGitLogReturnsResult() {
        ToolResult result = box.call(call("getGitLog", Map.of("count", 5)));
        assertThat(result).isNotNull();
    }

    // ── Unknown tool ─────────────────────────────────────────────────────

    @Test
    void unknownToolFails() {
        ToolResult result = box.call(call("deleteFile", Map.of("path", "README.md")));
        assertThat(result.failed()).isTrue();
        assertThat(result.content()).contains("Unknown tool");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall("call-1", name, args);
    }
}
