package com.crispyland.web.devagent;

import com.crispyland.agent.llm.ToolBox;
import com.crispyland.agent.llm.ToolCall;
import com.crispyland.agent.llm.ToolResult;
import com.crispyland.agent.llm.ToolSpec;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A {@link ToolBox} that gives the LLM read/write access to a local project directory.
 * All paths are resolved relative to {@code projectRoot} and security-checked.
 */
public class FileToolBox implements ToolBox {

    /** Proposed change accumulated during the tool loop. */
    public record ProposedChange(String path, ChangeType type, String reason, String unifiedDiff) {}

    public enum ChangeType { EDIT, NEW_FILE }

    private static final String SERVER = "devagent";

    /** Specific secret file patterns — NOT keyword-based, so TokenUsage.java is fine. */
    private static final List<String> BLOCKED_PATTERNS = List.of(
            ".env", ".env.*",
            "credentials.json", "client_secret*.json",
            "token.json", "StoredCredential",
            ".google-tokens/**",
            "**/*.pem", "**/*.key", "**/*.p12", "**/*.jks",
            ".idea/workspace.xml",
            ".git/**", "target/**", ".devagent/**"
    );

    private static final int MAX_LIST_FILES = 200;
    private static final int MAX_SEARCH_MATCHES = 50;
    private static final int MAX_READ_LINES = 500;
    private static final long MAX_FILE_SIZE = 500 * 1024L; // 500KB

    private final Path projectRoot;
    private final List<String> allowedWritePaths;
    private final int maxReadTokens;
    private final AtomicInteger tokensRead = new AtomicInteger(0);
    private final List<ProposedChange> proposedChanges = new ArrayList<>();
    private final List<PathMatcher> blockedMatchers;
    private final List<PathMatcher> writeMatchers;

    public FileToolBox(Path projectRoot, List<String> allowedWritePaths, int maxReadTokens) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.allowedWritePaths = allowedWritePaths != null ? allowedWritePaths : List.of();
        this.maxReadTokens = maxReadTokens;

        FileSystem fs = FileSystems.getDefault();
        this.blockedMatchers = BLOCKED_PATTERNS.stream()
                .map(p -> fs.getPathMatcher("glob:" + p))
                .toList();
        this.writeMatchers = this.allowedWritePaths.stream()
                .map(p -> fs.getPathMatcher("glob:" + p))
                .toList();
    }

    public List<ProposedChange> proposedChanges() {
        return Collections.unmodifiableList(proposedChanges);
    }

    // ── ToolBox contract ─────────────────────────────────────────────────────

    @Override
    public List<ToolSpec> available() {
        return List.of(
                new ToolSpec("listFiles",
                        "List files in a directory. Returns file names and sizes.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "Directory path relative to project root (default: \".\")"),
                                        "pattern", Map.of("type", "string", "description", "Glob pattern to filter files (default: \"*\")")),
                                "required", List.of())),

                new ToolSpec("searchFiles",
                        "Search file contents for a regex pattern. Returns matching lines with file paths and line numbers.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string", "description", "Regex pattern to search for"),
                                        "path", Map.of("type", "string", "description", "Directory to search in (default: \".\")"),
                                        "filePattern", Map.of("type", "string", "description",
                                                "Glob for file types (default: \"**/*.{java,xml,yml,yaml,md,html,json,properties}\")")),
                                "required", List.of("query"))),

                new ToolSpec("readFile",
                        "Read file contents with line numbers. Max 500 lines per call.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "File path relative to project root"),
                                        "startLine", Map.of("type", "integer", "description", "First line to read (1-based, default: 1)"),
                                        "endLine", Map.of("type", "integer", "description", "Last line to read (inclusive, default: startLine + 499)")),
                                "required", List.of("path"))),

                new ToolSpec("getGitStatus",
                        "Show current git branch and working tree status.",
                        Map.of("type", "object", "properties", Map.of(), "required", List.of())),

                new ToolSpec("getGitLog",
                        "Show recent git commits: hash, message, author, and date. Default 10 commits.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "count", Map.of("type", "integer", "description", "Number of commits to show (default: 10, max: 30)")),
                                "required", List.of())),

                new ToolSpec("proposeEdit",
                        "Propose an edit to an existing file. oldContent must match exactly once in the file.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "File path relative to project root"),
                                        "oldContent", Map.of("type", "string", "description", "Exact substring to replace (must match exactly once)"),
                                        "newContent", Map.of("type", "string", "description", "Replacement text"),
                                        "reason", Map.of("type", "string", "description", "Why this change is needed")),
                                "required", List.of("path", "oldContent", "newContent", "reason"))),

                new ToolSpec("proposeNewFile",
                        "Propose creating a new file.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "File path relative to project root"),
                                        "content", Map.of("type", "string", "description", "Full file content"),
                                        "reason", Map.of("type", "string", "description", "Why this file is needed")),
                                "required", List.of("path", "content", "reason")))
        );
    }

    @Override
    public ToolResult call(ToolCall call) {
        try {
            return switch (call.name()) {
                case "listFiles" -> listFiles(call);
                case "searchFiles" -> searchFiles(call);
                case "readFile" -> readFile(call);
                case "getGitStatus" -> getGitStatus(call);
                case "getGitLog" -> getGitLog(call);
                case "proposeEdit" -> proposeEdit(call);
                case "proposeNewFile" -> proposeNewFile(call);
                default -> ToolResult.failed(call, "Unknown tool: " + call.name(), SERVER);
            };
        } catch (Exception e) {
            return ToolResult.failed(call, "Tool error: " + e.getMessage(), SERVER);
        }
    }

    // ── Tool implementations ─────────────────────────────────────────────────

    private ToolResult listFiles(ToolCall call) throws IOException {
        String pathStr = stringArg(call, "path", ".");
        String pattern = stringArg(call, "pattern", "*");

        Path dir = resolveSafe(pathStr);
        if (dir == null) return ToolResult.failed(call, "Path blocked or outside project root.", SERVER);
        if (!Files.isDirectory(dir)) return ToolResult.failed(call, "Not a directory: " + pathStr, SERVER);

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> entries = new ArrayList<>();

        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                if (entries.size() >= MAX_LIST_FILES) return;
                if (matcher.matches(p.getFileName())) {
                    String rel = projectRoot.relativize(p).toString();
                    if (isBlocked(rel)) return;
                    try {
                        if (Files.isDirectory(p)) {
                            entries.add(rel + "/");
                        } else {
                            long size = Files.size(p);
                            entries.add(rel + " (" + humanSize(size) + ")");
                        }
                    } catch (IOException ignored) {}
                }
            });
        }

        if (entries.isEmpty()) return ToolResult.of(call, "No files found matching pattern '" + pattern + "' in " + pathStr, SERVER);
        return ToolResult.of(call, String.join("\n", entries), SERVER);
    }

    private ToolResult searchFiles(ToolCall call) throws IOException {
        String query = stringArg(call, "query", null);
        if (query == null || query.isBlank()) return ToolResult.failed(call, "query is required", SERVER);

        String pathStr = stringArg(call, "path", ".");
        String filePattern = stringArg(call, "filePattern", "**/*.{java,xml,yml,yaml,md,html,json,properties}");

        Path dir = resolveSafe(pathStr);
        if (dir == null) return ToolResult.failed(call, "Path blocked or outside project root.", SERVER);
        if (!Files.isDirectory(dir)) return ToolResult.failed(call, "Not a directory: " + pathStr, SERVER);

        Pattern regex;
        try {
            regex = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return ToolResult.failed(call, "Invalid regex: " + e.getMessage(), SERVER);
        }

        // Build both a recursive matcher and a flat-file matcher for the extension.
        // **/*.md won't match "README.md" (no directory component), so we also check *.ext.
        PathMatcher fileMatcher = FileSystems.getDefault().getPathMatcher("glob:" + filePattern);
        // Extract a flat pattern: **/*.{java,md} -> *.{java,md}
        String flatPattern = filePattern.replaceFirst("^\\*\\*/", "");
        PathMatcher flatMatcher = FileSystems.getDefault().getPathMatcher("glob:" + flatPattern);
        List<String> matches = new ArrayList<>();

        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (matches.size() >= MAX_SEARCH_MATCHES) return FileVisitResult.TERMINATE;
                String rel = projectRoot.relativize(file).toString();
                if (isBlocked(rel)) return FileVisitResult.CONTINUE;
                Path relFromRoot = projectRoot.relativize(file);
                Path relFromDir = dir.relativize(file);
                if (!fileMatcher.matches(file.getFileName())
                        && !fileMatcher.matches(relFromRoot)
                        && !fileMatcher.matches(relFromDir)
                        && !flatMatcher.matches(file.getFileName()))
                    return FileVisitResult.CONTINUE;
                if (attrs.size() > MAX_FILE_SIZE) return FileVisitResult.CONTINUE;
                if (isBinary(file)) return FileVisitResult.CONTINUE;

                List<String> lines = Files.readAllLines(file);
                int tokensEstimate = lines.stream().mapToInt(l -> l.length() / 4).sum();
                for (int i = 0; i < lines.size() && matches.size() < MAX_SEARCH_MATCHES; i++) {
                    if (regex.matcher(lines.get(i)).find()) {
                        matches.add(rel + ":" + (i + 1) + ": " + lines.get(i).strip());
                        chargeTokens(lines.get(i).length());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String rel = projectRoot.relativize(dir).toString();
                if (isBlocked(rel)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        if (matches.isEmpty()) return ToolResult.of(call, "No matches found for '" + query + "'", SERVER);

        String result = String.join("\n", matches);
        if (matches.size() >= MAX_SEARCH_MATCHES) {
            result += "\n... (truncated at " + MAX_SEARCH_MATCHES + " matches)";
        }
        return ToolResult.of(call, result, SERVER);
    }

    private ToolResult readFile(ToolCall call) throws IOException {
        String pathStr = stringArg(call, "path", null);
        if (pathStr == null || pathStr.isBlank()) return ToolResult.failed(call, "path is required", SERVER);

        Path file = resolveSafe(pathStr);
        if (file == null) return ToolResult.failed(call, "Path blocked or outside project root.", SERVER);
        if (!Files.exists(file)) return ToolResult.failed(call, "File not found: " + pathStr, SERVER);
        if (Files.isDirectory(file)) return ToolResult.failed(call, "Path is a directory: " + pathStr, SERVER);
        if (isBlocked(projectRoot.relativize(file).toString()))
            return ToolResult.failed(call, "Access denied: this file is blocked for security reasons.", SERVER);
        if (Files.size(file) > MAX_FILE_SIZE)
            return ToolResult.failed(call, "File too large (>" + humanSize(MAX_FILE_SIZE) + "): " + pathStr, SERVER);
        if (isBinary(file))
            return ToolResult.failed(call, "Binary file, cannot read: " + pathStr, SERVER);

        int startLine = intArg(call, "startLine", 1);
        int endLine = intArg(call, "endLine", startLine + MAX_READ_LINES - 1);
        if (startLine < 1) startLine = 1;
        if (endLine - startLine + 1 > MAX_READ_LINES) endLine = startLine + MAX_READ_LINES - 1;

        List<String> allLines = Files.readAllLines(file);
        int actualEnd = Math.min(endLine, allLines.size());
        int actualStart = Math.min(startLine, allLines.size() + 1);

        if (actualStart > allLines.size()) {
            return ToolResult.of(call, "File has " + allLines.size() + " lines, requested start at " + startLine, SERVER);
        }

        // Check token budget
        int charCount = 0;
        for (int i = actualStart - 1; i < actualEnd; i++) {
            charCount += allLines.get(i).length();
        }
        if (!chargeTokens(charCount)) {
            return ToolResult.failed(call, "Token budget exceeded (" + tokensRead.get()
                    + "/" + maxReadTokens + " tokens used). Work with what you have.", SERVER);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = actualStart - 1; i < actualEnd; i++) {
            sb.append(String.format("%4d | %s%n", i + 1, allLines.get(i)));
        }
        if (actualEnd < allLines.size()) {
            sb.append("... (").append(allLines.size() - actualEnd).append(" more lines)");
        }

        return ToolResult.of(call, sb.toString(), SERVER);
    }

    private ToolResult getGitStatus(ToolCall call) {
        try {
            String branch = runGit("git", "branch", "--show-current");
            String status = runGit("git", "status", "--short");
            String result = "Branch: " + branch.strip() + "\n";
            if (status.isBlank()) {
                result += "Working tree clean";
            } else {
                result += "Working tree:\n" + status;
            }
            return ToolResult.of(call, result, SERVER);
        } catch (Exception e) {
            return ToolResult.failed(call, "Git error: " + e.getMessage(), SERVER);
        }
    }

    private ToolResult getGitLog(ToolCall call) {
        try {
            int count = Math.min(intArg(call, "count", 10), 30);
            if (count < 1) count = 10;
            String log = runGit("git", "log", "-" + count, "--format=%h %ai %an: %s");
            return ToolResult.of(call, log.strip(), SERVER);
        } catch (Exception e) {
            return ToolResult.failed(call, "Git error: " + e.getMessage(), SERVER);
        }
    }

    private ToolResult proposeEdit(ToolCall call) throws IOException {
        String pathStr = stringArg(call, "path", null);
        String oldContent = stringArg(call, "oldContent", null);
        String newContent = stringArg(call, "newContent", null);
        String reason = stringArg(call, "reason", null);

        if (pathStr == null) return ToolResult.failed(call, "path is required", SERVER);
        if (oldContent == null) return ToolResult.failed(call, "oldContent is required", SERVER);
        if (newContent == null) return ToolResult.failed(call, "newContent is required", SERVER);
        if (reason == null) return ToolResult.failed(call, "reason is required", SERVER);

        Path file = resolveSafe(pathStr);
        if (file == null) return ToolResult.failed(call, "Path blocked or outside project root.", SERVER);
        if (!Files.exists(file)) return ToolResult.failed(call, "File not found: " + pathStr, SERVER);

        String rel = projectRoot.relativize(file).toString();
        if (isBlocked(rel)) return ToolResult.failed(call, "Access denied: this file is blocked.", SERVER);
        if (!isWriteAllowed(rel)) return ToolResult.failed(call, "Write not allowed to '" + rel
                + "'. Use --allow flag to permit writes to this path.", SERVER);

        String fileContent = Files.readString(file);

        // Exact-once match check
        int count = countOccurrences(fileContent, oldContent);
        if (count == 0) return ToolResult.failed(call, "Content not found in " + rel
                + ". Make sure oldContent is an exact substring of the file.", SERVER);
        if (count > 1) return ToolResult.failed(call,
                "Ambiguous match (" + count + " occurrences) in " + rel
                        + ". Provide more surrounding context to make oldContent unique.", SERVER);

        // Generate diff
        String modified = fileContent.replace(oldContent, newContent);
        List<String> oldLines = Arrays.asList(fileContent.split("\n", -1));
        List<String> newLines = Arrays.asList(modified.split("\n", -1));

        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + rel, "b/" + rel, oldLines, patch, 3);

        // Prepend git-compatible header
        String diffHeader = "diff --git a/" + rel + " b/" + rel + "\n";
        String diff = diffHeader + String.join("\n", unified);

        proposedChanges.add(new ProposedChange(rel, ChangeType.EDIT, reason, diff));
        return ToolResult.of(call, "Edit proposed for " + rel + ":\n" + diff, SERVER);
    }

    private ToolResult proposeNewFile(ToolCall call) {
        String pathStr = stringArg(call, "path", null);
        String content = stringArg(call, "content", null);
        String reason = stringArg(call, "reason", null);

        if (pathStr == null) return ToolResult.failed(call, "path is required", SERVER);
        if (content == null) return ToolResult.failed(call, "content is required", SERVER);
        if (reason == null) return ToolResult.failed(call, "reason is required", SERVER);

        Path file = resolveSafe(pathStr);
        if (file == null) return ToolResult.failed(call, "Path blocked or outside project root.", SERVER);
        if (Files.exists(file)) return ToolResult.failed(call, "File already exists: " + pathStr
                + ". Use proposeEdit to modify existing files.", SERVER);

        String rel = projectRoot.relativize(file).toString();
        if (isBlocked(rel)) return ToolResult.failed(call, "Access denied: this path is blocked.", SERVER);
        if (!isWriteAllowed(rel)) return ToolResult.failed(call, "Write not allowed to '" + rel
                + "'. Use --allow flag to permit writes to this path.", SERVER);

        // Generate git-compatible new file diff
        List<String> newLines = Arrays.asList(content.split("\n", -1));
        List<String> emptyList = List.of();

        Patch<String> patch = DiffUtils.diff(emptyList, newLines);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                "/dev/null", "b/" + rel, emptyList, patch, 3);

        // Build git-compatible header
        StringBuilder diff = new StringBuilder();
        diff.append("diff --git a/").append(rel).append(" b/").append(rel).append("\n");
        diff.append("new file mode 100644\n");
        for (String line : unified) {
            diff.append(line).append("\n");
        }

        String diffStr = diff.toString().stripTrailing();
        proposedChanges.add(new ProposedChange(rel, ChangeType.NEW_FILE, reason, diffStr));
        return ToolResult.of(call, "New file proposed: " + rel + "\n" + diffStr, SERVER);
    }

    // ── Security helpers ─────────────────────────────────────────────────────

    /** Resolve a relative path safely. Returns null if the path escapes the project root. */
    Path resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return projectRoot;
        Path resolved = projectRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(projectRoot)) return null;
        return resolved;
    }

    /** Check if a relative path matches any blocked pattern. */
    boolean isBlocked(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return false;
        Path relPath = Path.of(relativePath);
        for (PathMatcher matcher : blockedMatchers) {
            if (matcher.matches(relPath)) return true;
        }
        // Also check each component for directory-level blocks
        for (int i = 1; i <= relPath.getNameCount(); i++) {
            Path sub = relPath.subpath(0, i);
            for (PathMatcher matcher : blockedMatchers) {
                if (matcher.matches(sub)) return true;
            }
        }
        return false;
    }

    /** Check if a relative path is allowed for writes. */
    boolean isWriteAllowed(String relativePath) {
        Path relPath = Path.of(relativePath);
        for (PathMatcher matcher : writeMatchers) {
            if (matcher.matches(relPath)) return true;
        }
        return false;
    }

    /** Check first 8KB for null bytes to detect binary files. */
    static boolean isBinary(Path file) {
        try (var is = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read = is.read(buf);
            if (read <= 0) return false;
            for (int i = 0; i < read; i++) {
                if (buf[i] == 0) return true;
            }
            return false;
        } catch (IOException e) {
            return true; // err on the side of caution
        }
    }

    // ── Utility helpers ──────────────────────────────────────────────────────

    private boolean chargeTokens(int charCount) {
        int tokens = charCount / 4;
        int newTotal = tokensRead.addAndGet(tokens);
        return newTotal <= maxReadTokens;
    }

    private String stringArg(ToolCall call, String key, String defaultValue) {
        Object val = call.arguments().get(key);
        if (val == null) return defaultValue;
        return val.toString();
    }

    private int intArg(ToolCall call, String key, int defaultValue) {
        Object val = call.arguments().get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String runGit(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        }
        if (!proc.waitFor(10, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("Git command timed out");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("Git failed (exit " + proc.exitValue() + "): " + output);
        }
        return output;
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }
}
