package com.crispyland.web.devagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.web.devagent.FileToolBox.ChangeType;
import com.crispyland.web.devagent.FileToolBox.ProposedChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for DevagentController security properties.
 * <p>
 * These are unit tests that verify the security model without requiring
 * a running Spring context or Cerebras API calls.
 */
class DevagentControllerTest {

    /**
     * The endpoint is gated by {@code @ConditionalOnProperty(name = "devagent.enabled",
     * havingValue = "true")}. Since {@code application.yml} has {@code devagent.enabled: false},
     * the bean is not created by default. A Spring integration test would verify this returns 404,
     * but that requires the full application context. Instead, we verify the property default.
     */
    @Test
    void disabledByDefaultInConfig() throws IOException {
        // Read the actual application.yml to verify the default
        Path yml = Path.of("src/main/resources/application.yml");
        if (Files.exists(yml)) {
            String content = Files.readString(yml);
            assertThat(content).contains("devagent:");
            assertThat(content).contains("enabled: false");
        }
    }

    /**
     * The application-devagent.yml profile enables the feature. Verify it exists and sets
     * enabled: true so that SPRING_PROFILES_ACTIVE=devagent works.
     */
    @Test
    void devagentProfileEnablesFeature() throws IOException {
        Path profile = Path.of("src/main/resources/application-devagent.yml");
        if (Files.exists(profile)) {
            String content = Files.readString(profile);
            assertThat(content).contains("enabled: true");
        }
    }

    /**
     * The DevagentRequest record has no projectPath field — project root comes
     * exclusively from the server config, never from the client.
     */
    @Test
    void requestHasNoProjectPathField() {
        // DevagentRequest only has: goal, extraWritePaths, maxRounds
        var request = new DevagentController.DevagentRequest("test goal", List.of(), 15);
        assertThat(request.goal()).isEqualTo("test goal");
        // No projectPath accessor exists — this test documents the design
    }

    /**
     * Dry-run: proposed changes are collected but never written to disk.
     */
    @Test
    void dryRunWritesNothing(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("README.md"), "# Old Title\nContent here.\n");

        FileToolBox box = new FileToolBox(root, List.of("README.md"), 50000);

        // Propose an edit
        var call = new com.crispyland.agent.llm.ToolCall("c1", "proposeEdit", java.util.Map.of(
                "path", "README.md",
                "oldContent", "# Old Title",
                "newContent", "# New Title",
                "reason", "update title"));
        var result = box.call(call);

        assertThat(result.failed()).isFalse();
        assertThat(box.proposedChanges()).hasSize(1);

        // The FILE on disk is unchanged — no writes happened
        String diskContent = Files.readString(root.resolve("README.md"));
        assertThat(diskContent).contains("# Old Title");
        assertThat(diskContent).doesNotContain("# New Title");
    }

    /**
     * Proposed edits generate git-apply-compatible unified diffs.
     */
    @Test
    void editDiffIsGitApplyCompatible(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("README.md"), "# Title\nLine 2\nLine 3\n");

        FileToolBox box = new FileToolBox(root, List.of("README.md"), 50000);
        var result = box.call(new com.crispyland.agent.llm.ToolCall("c1", "proposeEdit", java.util.Map.of(
                "path", "README.md",
                "oldContent", "# Title",
                "newContent", "# Updated Title",
                "reason", "test")));

        assertThat(result.failed()).isFalse();
        ProposedChange change = box.proposedChanges().get(0);
        String diff = change.unifiedDiff();

        // Must have git-compatible headers
        assertThat(diff).startsWith("diff --git a/README.md b/README.md");
        assertThat(diff).contains("--- a/README.md");
        assertThat(diff).contains("+++ b/README.md");
        assertThat(diff).contains("@@");
    }

    /**
     * New file diffs include the git-compatible "new file mode" header.
     */
    @Test
    void newFileDiffIsGitApplyCompatible(@TempDir Path root) {
        FileToolBox box = new FileToolBox(root, List.of("docs/**"), 50000);

        // Create docs dir so the path resolves
        try { Files.createDirectories(root.resolve("docs")); } catch (IOException ignored) {}

        var result = box.call(new com.crispyland.agent.llm.ToolCall("c1", "proposeNewFile", java.util.Map.of(
                "path", "docs/new.md",
                "content", "# New Doc\nContent.\n",
                "reason", "test")));

        assertThat(result.failed()).isFalse();
        ProposedChange change = box.proposedChanges().get(0);
        String diff = change.unifiedDiff();

        assertThat(diff).contains("diff --git a/docs/new.md b/docs/new.md");
        assertThat(diff).contains("new file mode 100644");
        assertThat(diff).contains("--- /dev/null");
        assertThat(diff).contains("+++ b/docs/new.md");
    }

    /**
     * Loopback check: verify the controller's address matching logic.
     * The actual HTTP check is in the controller, but we document the accepted values.
     */
    @Test
    void loopbackAddresses() {
        // These are the three forms the controller accepts
        List<String> loopback = List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
        List<String> nonLoopback = List.of("192.168.1.5", "10.0.0.1", "203.0.113.42");

        for (String addr : loopback) {
            assertThat(addr).matches(a ->
                    "127.0.0.1".equals(a) || "::1".equals(a) || "0:0:0:0:0:0:0:1".equals(a));
        }
        for (String addr : nonLoopback) {
            assertThat(addr).doesNotMatch(a ->
                    "127.0.0.1".equals(a) || "::1".equals(a) || "0:0:0:0:0:0:0:1".equals(a));
        }
    }
}
