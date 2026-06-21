package com.ai.reviewer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LocalCodeReviewer reads the current git diff, filters changed code blocks,
 * optionally annotates new lines with file line numbers, and sends the result
 * to a local Ollama model for a code review response.
 */
public class LocalCodeReviewer {

    private static final Logger LOGGER = Logger.getLogger(LocalCodeReviewer.class.getName());
    private static final Pattern INLINE_STATUS_PATTERN = Pattern.compile("(?i)^(.+?):\\s*STATUS:\\s*\\[?(FAILED|PASSED)\\]?");
    private final HttpClient httpClient;

    public LocalCodeReviewer() {
        this(HttpClient.newHttpClient());
    }

    public LocalCodeReviewer(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Entry point for command-line execution.
     * Runs the reviewer and waits for the review task to complete.
     */

    public static void main(String[] args) {
        LocalCodeReviewer reviewer = new LocalCodeReviewer();
        int exitCode = 0;
        try {
            String reviewText = reviewer.runReview().join();
            List<ReviewFinding> findings = parseReviewFindings(reviewText);
            List<ReviewFinding> failedFindings = findings.stream()
                    .filter(finding -> "FAILED".equalsIgnoreCase(finding.status))
                    .toList();
            if (!failedFindings.isEmpty()) {
                System.err.println("[ERROR] AI Code Quality Gate detected failures.");
                if (reviewer.isGitHubContext()) {
                    System.err.println("[INFO] Posting line-level PR review comments to GitHub.");
                    reviewer.postGitHubReviewComments(failedFindings);
                } else {
                    System.err.println("[WARN] GitHub review context is not configured; comments will not be posted.");
                }
                exitCode = 1;
            } else {
                System.out.println("[INFO] AI Code Quality Gate passed. No failed categories detected.");
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Exception during execution: " + e.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    /**
     * Fetches the current git diff, filters it, and sends it to the local Ollama service.
     * Returns a CompletableFuture containing the review text or an error if git diff fails.
     */
    public CompletableFuture<String> runReview() {
        try {
            System.out.println(">>> Isolating local changes via 'git diff HEAD'...");
            String diffText = getGitDiff();
            String filteredDiff = filterDiff(diffText);
            if (filteredDiff.trim().isEmpty()) {
                System.out.println("[INFO] No git changes detected.");
                System.out.println(">>> Tip: Edit or stage files in git before running the code reviewer.");
                return CompletableFuture.completedFuture("No changes");
            }
            System.out.println(">>> Sending changes to local Ollama (model: qwen2.5-coder:14b)...");
            return sendToOllama(filteredDiff);
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to execute git diff: " + e.getMessage());
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    /**
     * Reads the local git diff against HEAD for the current repository.
     * Excludes the reviewer source file so the tool does not attempt to review itself.
     */
    public String getGitDiff() throws Exception {
        if (isGitHubContext()) {
            return getPullRequestDiffFromGitHub();
        }
        return getLocalGitDiff();
    }

    /**
     * Reads local working tree changes via git diff against HEAD.
     * Includes staged and unstaged changes while excluding this reviewer class.
     */
    private String getLocalGitDiff() throws Exception {
        // Natively targets both unstaged and staged changes in a single raw stream
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "HEAD", "--", ".", ":!**/LocalCodeReviewer.java");
        Process process = pb.start();
        String diffText;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            diffText = reader.lines().collect(Collectors.joining("\n"));
        }
        process.waitFor();
        return diffText;
    }

    /**
     * Fetches pull request diff content directly from GitHub when running in CI PR context.
     */
    private String getPullRequestDiffFromGitHub() throws Exception {
        String repo = getGitHubRepository();
        String prNumber = getGitHubPullRequestNumber();
        String apiBase = getGitHubApiBase();
        String token = getGitHubToken();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3.diff")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch PR diff from GitHub: " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    /**
     * Determines whether all required GitHub PR environment context is present.
     */
    private boolean isGitHubContext() {
        return Optional.ofNullable(System.getenv("GITHUB_REPOSITORY")).filter(s -> !s.isBlank()).isPresent()
                && (Optional.ofNullable(System.getenv("GITHUB_PR_NUMBER")).filter(s -> !s.isBlank()).isPresent()
                || Optional.ofNullable(System.getenv("CHANGE_ID")).filter(s -> !s.isBlank()).isPresent())
                && Optional.ofNullable(System.getenv("GITHUB_TOKEN")).filter(s -> !s.isBlank()).isPresent();
    }

    /**
     * Resolves repository identifier in owner/repo format from environment or Jenkins CHANGE_URL.
     */
    private String getGitHubRepository() {
        String repository = System.getenv("GITHUB_REPOSITORY");
        if (repository != null && !repository.isBlank()) {
            return repository;
        }
        String changeUrl = System.getenv("CHANGE_URL");
        if (changeUrl != null && !changeUrl.isBlank()) {
            try {
                URI uri = URI.create(changeUrl);
                String path = uri.getPath(); // e.g. /owner/repo/pull/123
                String[] segments = path.split("/");
                List<String> cleaned = new ArrayList<>();
                for (String s : segments) {
                    if (s != null && !s.isBlank()) cleaned.add(s);
                }
                if (cleaned.size() >= 2) {
                    // owner = cleaned[0], repo = cleaned[1]
                    return cleaned.get(0) + "/" + cleaned.get(1);
                }
            } catch (Exception e) {
                // fall through to error below
            }
        }
        throw new IllegalStateException("Missing required GitHub repository context: GITHUB_REPOSITORY or CHANGE_URL");
    }

    /**
     * Resolves pull request number from explicit env var or Jenkins CHANGE_ID fallback.
     */
    private String getGitHubPullRequestNumber() {
        String prNumber = System.getenv("GITHUB_PR_NUMBER");
        if (prNumber != null && !prNumber.isBlank()) {
            return prNumber;
        }
        prNumber = System.getenv("CHANGE_ID");
        if (prNumber != null && !prNumber.isBlank()) {
            return prNumber;
        }
        throw new IllegalStateException("Missing required GitHub PR number context: GITHUB_PR_NUMBER or CHANGE_ID");
    }

    /**
     * Returns GitHub token used for PR API calls.
     */
    private String getGitHubToken() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing required GitHub token: GITHUB_TOKEN");
        }
        return token;
    }

    /**
     * Resolves GitHub API base URL with a sensible default for github.com.
     */
    private String getGitHubApiBase() {
        return Optional.ofNullable(System.getenv("GITHUB_API_URL")).filter(s -> !s.isBlank()).orElse("https://api.github.com");
    }

    /**
     * Resolves commit SHA for comment anchoring, preferring Jenkins GIT_COMMIT.
     */
    private String getGitCommitSha() throws Exception {
        String gitCommit = System.getenv("GIT_COMMIT");
        if (gitCommit != null && !gitCommit.isBlank()) {
            return gitCommit;
        }
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
        Process process = pb.start();
        String sha;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            sha = reader.lines().collect(Collectors.joining("\n")).trim();
        }
        process.waitFor();
        if (sha.isBlank()) {
            throw new RuntimeException("Cannot determine current Git commit SHA.");
        }
        return sha;
    }

    /**
     * Removes diff blocks that contain only metadata and no actual added or removed code.
     * This keeps the review payload focused on changed source lines only.
     */
    public static String filterDiff(String rawDiff) {
        if (rawDiff == null || rawDiff.trim().isEmpty()) {
            return "";
        }
        String[] blocks = rawDiff.split("(?=diff --git )");
        StringBuilder filtered = new StringBuilder();
        for (String block : blocks) {
            if (block.trim().isEmpty()) {
                continue;
            }
            boolean hasChanges = false;
            String[] lines = block.split("\n");
            for (String line : lines) {
                // Check for lines starting with + or - that are not part of diff file headers
                // (+++ or ---)
                if ((line.startsWith("+") && !line.startsWith("+++")) ||
                        (line.startsWith("-") && !line.startsWith("---"))) {
                    hasChanges = true;
                    break;
                }
            }
            if (hasChanges) {
                filtered.append(block);
            }
        }
        return filtered.toString();
    }

    /**
     * Adds [Line N] annotations to added lines in the diff so the reviewer can
     * see the exact destination line number for new code.
     */
    public static String annotateDiffWithLineNumbers(String diffText) {
        if (diffText == null || diffText.isEmpty()) {
            return diffText;
        }

        StringBuilder annotated = new StringBuilder();
        String[] lines = diffText.split("\n");
        int currentNewLine = -1;

        for (String line : lines) {
            if (line.startsWith("@@")) {
                // Example header: @@ -13,6 +13,30 @@
                String[] parts = line.split(" ");
                for (String part : parts) {
                    if (part.startsWith("+")) {
                        String[] range = part.substring(1).split(",");
                        try {
                            currentNewLine = Integer.parseInt(range[0]);
                        } catch (NumberFormatException ignored) {
                            currentNewLine = -1;
                        }
                        break;
                    }
                }
                annotated.append(line).append("\n");
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                if (currentNewLine > 0) {
                    annotated.append("[Line ").append(currentNewLine).append("] ").append(line).append("\n");
                    currentNewLine++;
                } else {
                    annotated.append(line).append("\n");
                }
            } else {
                annotated.append(line).append("\n");
                if (line.startsWith(" ") && currentNewLine > 0) {
                    currentNewLine++;
                }
            }
        }

        return annotated.toString();
    }

    /**
     * Parses the AI review feedback text into structured ReviewFinding objects.
     * 
     * This parser handles variable AI output formats:
     * - Categories with or without **bold** or [brackets]
     * - STATUS field that may appear inline (STATUS: FAILED) or on separate lines
     * - Multi-line fields (Problem, AI Suggested Fix can span multiple lines)
     * - File paths, line numbers, problem descriptions, and fixes
     * 
     * CRITICAL: GitHub PR inline comments require STATUS: FAILED to be detected so the
     * reviewer can post comments. This parser splits blocks by blank lines and extracts
     * each field robustly using regex patterns that work with different AI output layouts.
     * 
     * @param reviewText The raw feedback text from Ollama AI model
     * @return List of ReviewFinding objects with category, status, file, line, problem, and fix
     */
    static List<ReviewFinding> parseReviewFindings(String reviewText) {
        List<ReviewFinding> findings = new ArrayList<>();
        if (reviewText == null || reviewText.isBlank()) {
            return findings;
        }

        // Normalize line endings and split blocks by blank lines (each block = one category's review)
        String normalized = reviewText.replace("\r\n", "\n").replace("\r", "\n");
        String[] blocks = normalized.split("\\n\\s*\\n+");

        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Extract category name from first line, removing decorations like **Category**:
            // This handles formats like "**Playwright Web Assertions:**" or "[Category]:"
            String[] lines = trimmed.split("\\n");
            String categoryLine = lines[0].trim();

            // Some model responses emit "Category: STATUS: FAILED" on one line.
            String status = "PASSED";
            Matcher inlineStatusMatcher = INLINE_STATUS_PATTERN.matcher(categoryLine);
            if (inlineStatusMatcher.find()) {
                categoryLine = inlineStatusMatcher.group(1).trim();
                status = inlineStatusMatcher.group(2).toUpperCase();
            }

            String category = categoryLine.replaceAll("^\\*\\*", "")
                    .replaceAll("\\*\\*$", "")
                    .replaceAll("^\\[", "")
                    .replaceAll("\\]$", "")
                    .replaceAll(":$", "")
                    .trim();

            // Extract STATUS field which may be inline (STATUS: FAILED) or on its own line
            // This is critical for detecting failed checks so GitHub comments can be posted
            if ("PASSED".equals(status)) {
                status = extractSingleLineField(trimmed, "STATUS")
                        .map(value -> value.replaceAll("\\[|\\]", "").trim().toUpperCase())
                        .orElse("PASSED");
            }

            // Extract file path, line number, problem description, and suggested fix
            // Using field extraction methods that handle multi-line content
            String file = extractSingleLineField(trimmed, "File").orElse("");
            String lineValue = extractSingleLineField(trimmed, "Line")
                    .or(() -> extractSingleLineField(trimmed, "Lines"))
                    .orElse("0");
            int lineNumber = 0;
            try {
                // Handle line ranges (e.g., "41, 42" or "350-360") by taking the first number
                String firstLineToken = lineValue.split("[,\\s-]+")[0].trim();
                lineNumber = Integer.parseInt(firstLineToken);
            } catch (Exception ignored) {
                lineNumber = 0;
            }
            String problem = extractFieldBody(trimmed, "Problem").orElse("");
            String suggestedFix = extractFieldBody(trimmed, "AI Suggested Fix").orElse("");

            ReviewFinding finding = new ReviewFinding();
            finding.category = category.replaceAll("\\*|\\[|\\]", "").trim();
            finding.status = status.isBlank() ? "PASSED" : status;
            finding.file = file.trim();
            finding.line = lineNumber;
            finding.problem = problem.trim();
            finding.suggestedFix = suggestedFix.trim();
            findings.add(finding);
        }

        return findings;
    }

    /**
     * Extracts a single-line field value from review text.
     * Example: "STATUS: FAILED" → returns "FAILED"
     * 
     * @param text The review block text to search
     * @param fieldName The field name (e.g., "STATUS", "Line", "File")
     * @return Optional containing the field value, or empty if not found
     */
    private static Optional<String> extractSingleLineField(String text, String fieldName) {
        Pattern fieldPattern = Pattern.compile("(?im)^\\s*" + Pattern.quote(fieldName) + ":\\s*(.*)$");
        Matcher matcher = fieldPattern.matcher(text);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * Extracts a potentially multi-line field value from review text.
     * Example: "Problem: ... content ...\nAI Suggested Fix: ..." → returns multi-line content before next field
     * 
     * Used for fields like "Problem" and "AI Suggested Fix" which may span multiple lines.
     * 
     * @param text The review block text to search
     * @param fieldName The field name (e.g., "Problem", "AI Suggested Fix")
     * @return Optional containing the field value including all lines until the next field, or empty if not found
     */
    private static Optional<String> extractFieldBody(String text, String fieldName) {
        Pattern fieldPattern = Pattern.compile("(?ims)" + Pattern.quote(fieldName) + ":\\s*(.*?)(?=^\\s*[A-Za-z0-9 _\\[\\]-]+?:\\s*|\\z)");
        Matcher matcher = fieldPattern.matcher(text);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * Posts all FAILED findings as GitHub inline comments and keeps posting on per-item failures.
     */
    private void postGitHubReviewComments(List<ReviewFinding> findings) throws Exception {
        String repo = getGitHubRepository();
        String prNumber = getGitHubPullRequestNumber();
        String apiBase = getGitHubApiBase();
        String commitSha = getGitCommitSha();
        List<String> changedFiles = getPullRequestChangedFiles(repo, prNumber, apiBase);

        int postedCount = 0;
        int failedCount = 0;

        for (ReviewFinding finding : findings) {
            Optional<String> resolvedPath = resolveReviewFindingPath(finding.file, changedFiles);
            if (finding.line <= 0 || resolvedPath.isEmpty()) {
                LOGGER.warning(() -> "Skipping invalid review finding for GitHub comment: file="
                        + finding.file + ", line=" + finding.line + ", category=" + finding.category);
                failedCount++;
                continue;
            }

            finding.file = resolvedPath.get();

            try {
                createGitHubPullRequestComment(repo, prNumber, apiBase, commitSha, finding);
                postedCount++;
            } catch (Exception ex) {
                LOGGER.warning(() -> "Failed to post GitHub comment for file="
                        + finding.file + ", line=" + finding.line + ": " + ex.getMessage());
                failedCount++;
            }
        }

        LOGGER.info("GitHub inline comments posting summary: posted=" + postedCount + ", failed=" + failedCount);

        if (postedCount == 0 && !findings.isEmpty()) {
            throw new RuntimeException("Failed to post any GitHub inline comments for FAILED findings.");
        }
    }

    /**
     * Retrieves changed file paths for the pull request so bare filenames from AI output
     * can be resolved to repository-relative paths required by GitHub inline comments API.
     */
    private List<String> getPullRequestChangedFiles(String repo, String prNumber, String apiBase) throws Exception {
        List<String> changedFiles = new ArrayList<>();
        Gson gson = new Gson();

        for (int page = 1; page <= 10; page++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("%s/repos/%s/pulls/%s/files?per_page=100&page=%d", apiBase, repo, prNumber, page)))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Authorization", "Bearer " + getGitHubToken())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch PR files from GitHub: " + response.statusCode() + " " + response.body());
            }

            JsonArray files = gson.fromJson(response.body(), JsonArray.class);
            if (files == null || files.isEmpty()) {
                break;
            }

            for (int i = 0; i < files.size(); i++) {
                JsonObject fileObj = files.get(i).getAsJsonObject();
                if (fileObj.has("filename")) {
                    changedFiles.add(normalizePath(fileObj.get("filename").getAsString()));
                }
            }

            if (files.size() < 100) {
                break;
            }
        }

        return changedFiles;
    }

    /**
     * Resolves AI-reported file identifiers to exact repository-relative paths.
     * Supports exact paths and unique basename matches (e.g. LocalCodeReviewer.java).
     */
    private Optional<String> resolveReviewFindingPath(String rawPath, List<String> changedFiles) {
        if (rawPath == null || rawPath.isBlank()) {
            return Optional.empty();
        }

        String normalizedRawPath = normalizePath(rawPath);

        if (changedFiles.contains(normalizedRawPath)) {
            return Optional.of(normalizedRawPath);
        }

        List<String> suffixMatches = changedFiles.stream()
                .filter(path -> path.endsWith("/" + normalizedRawPath))
                .toList();
        if (suffixMatches.size() == 1) {
            return Optional.of(suffixMatches.get(0));
        }

        String rawFileName = fileNamePart(normalizedRawPath);
        List<String> fileNameMatches = changedFiles.stream()
                .filter(path -> fileNamePart(path).equals(rawFileName))
                .toList();
        if (fileNameMatches.size() == 1) {
            return Optional.of(fileNameMatches.get(0));
        }

        return Optional.empty();
    }

    /**
     * Normalizes file paths to repository-style separators and strips leading ./.
     */
    private String normalizePath(String path) {
        return path.replace('\\', '/').replaceFirst("^\\./", "").trim();
    }

    /**
     * Extracts filename component from a path.
     */
    private String fileNamePart(String path) {
        int slashIndex = path.lastIndexOf('/');
        return slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
    }

    /**
     * Posts a single AI review finding as an inline comment on a GitHub PR.
     * 
     * GitHub PR comment API requires:
     * - commit_id: the commit SHA where the comment should appear
     * - path: the file path relative to repo root
     * - line: the line number in the file (on the RIGHT/new side of the diff)
     * - body: the comment text
     * 
     * Uses java.util.logging for debug output so it doesn't interfere with production logs.
     * 
     * @param repo Repository in format "owner/repo"
     * @param prNumber The PR number
     * @param apiBase GitHub API base URL (usually https://api.github.com)
     * @param commitSha The commit SHA for this PR
     * @param finding The ReviewFinding containing file, line, problem, and fix
     * @throws Exception If GitHub API returns a non-2xx status code
     */
    private void createGitHubPullRequestComment(String repo,
                                                String prNumber,
                                                String apiBase,
                                                String commitSha,
                                                ReviewFinding finding) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("body", buildCommentBody(finding));
        payload.addProperty("path", finding.file);
        payload.addProperty("line", finding.line);
        payload.addProperty("side", "RIGHT");
        payload.addProperty("commit_id", commitSha);
        String jsonBody = new Gson().toJson(payload);
        
        // Log debug info before posting (useful for troubleshooting missing GitHub comments)
        LOGGER.fine(() -> "Posting PR comment to: " + repo + " PR:" + prNumber + " file:" + finding.file + " line:" + finding.line);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s/comments", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + getGitHubToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        
        // Log GitHub API response for debugging (status code and response body help identify why comments failed)
        LOGGER.fine(() -> "GitHub response: " + response.statusCode() + " body: " + response.body());
        
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new RuntimeException("GitHub PR comment creation failed: " + response.statusCode() + " " + response.body());
        }
    }

    private String buildCommentBody(ReviewFinding finding) {
        return String.format("[AI CODE QUALITY GATE] %s FAILURE\nProblem: %s\nSuggested fix:\n%s",
                finding.category,
                finding.problem,
                finding.suggestedFix);
    }

    /**
     * Represents a single AI code review finding with category, status, file, line, problem, and suggested fix.
     * 
     * Package-visible (not private) to allow unit testing of the parser. Each finding becomes a GitHub PR inline comment
     * if status is FAILED.
     */
    static class ReviewFinding {
        String category;
        String status;
        String file;
        int line;
        String problem;
        String suggestedFix;
    }

    /**
     * Sends the prepared diff text to the local Ollama HTTP API and returns the model response.
     * The model is expected to return a review string that is printed to stdout.
     */
    public CompletableFuture<String> sendToOllama(String diffText) {
        String annotatedDiff = annotateDiffWithLineNumbers(diffText);

        // String Hygiene Rule: Strictly apply string sanitization on the raw diff text
        // before payload assembly
        String sanitizedDiff = annotatedDiff
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        String systemPrompt = """
                You are an exceptionally strict automated Code Reviewer specializing in Java Playwright test frameworks.
                Review the provided plain text modifications line-by-line.

                CRITICAL SORTING RULES:
                - Assess each code change independently. Place a defect strictly in its single most relevant category. Do not repeat issues.
                - Playwright Web Assertions: Only flag legacy assertions (e.g., plain java assert, JUnit, or TestNG assertions).
                - Locator Robustness: Only flag brittle locators (e.g., absolute XPaths, long dynamic CSS).
                - Hardcoded Configurations: Only flag hardcoded synchronizations (e.g., Thread.sleep).
                - Logging: Only flag plain standard output statements (e.g., System.out.println, printStackTrace).

                UNIVERSAL OUTPUT FORMAT:
                - If a category passes, print exactly: [Category Name]: STATUS: [PASSED]
                - If a category fails, print exactly:
                   [Category Name]: STATUS: [FAILED]
                   File: [Provide the file path]
                   Line: [Provide the line number if visible]
                   Problem: [Clear explanation of why the code violates automation best practices]
                   AI Suggested Fix:
                   [Provide the exact, syntactically correct Java code snippet that replaces the bad code completely using active variables like testContext.getPage(). Do not use markdown backticks or asterisks.]
                """;

        String sanitizedSystemPrompt = systemPrompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        // Assemble raw JSON manually applying the sanitization
        String rawJson = "{"
                + "\"model\":\"qwen2.5-coder:14b\"," // Upgraded brain
                + "\"stream\":false,"
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + sanitizedSystemPrompt + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + sanitizedDiff + "\"}"
                + "],"
                + "\"options\":{"
                + "\"temperature\":0.0,"
                + "\"top_p\":0.1,"
                + "\"num_ctx\":16384" // Injected 16k Context Window mapping here
                + "}"
                + "}";
        // Use GSON to parse the manual JSON payload to ensure validity and format
        // correctly
        Gson gson = new Gson();
        JsonObject payloadObject = gson.fromJson(rawJson, JsonObject.class);
        String jsonPayload = gson.toJson(payloadObject);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        System.err.println("\n[ERROR] Failed to connect to local Ollama service.");
                        System.err.println(
                                "[HELP] Please ensure Ollama is installed and running via: ollama run qwen2.5-coder:14b");
                        System.err.println("[HELP] Ensure the Ollama port is accessible at: http://localhost:11434");
                        throw new RuntimeException("Ollama connection failed", throwable);
                    }
                    if (response.statusCode() != 200) {
                        System.err.println("\n[ERROR] Ollama returned non-200 status code: " + response.statusCode());
                        System.err.println("Response body: " + response.body());
                        throw new RuntimeException("Ollama non-200 status code: " + response.statusCode());
                    }
                    JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                    String feedback;
                    if (jsonResponse.has("message")) {
                        feedback = jsonResponse.getAsJsonObject("message").get("content").getAsString();
                    } else {
                        feedback = jsonResponse.getAsJsonArray("choices")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("message").get("content").getAsString();
                    }
                    System.out.println("\n==================================================");
                    System.out.println("                AI CODE REVIEW FEEDBACK           ");
                    System.out.println("==================================================");
                    System.out.println(feedback);
                    System.out.println("==================================================");
                    return feedback;
                });
    }
}
