package com.ai.reviewer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
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
        private static final Pattern REVIEW_FINDING_PATTERN = Pattern.compile(
            "(?:\\*\\*|\\[)?([A-Za-z0-9\\s&/\\-_\\(\\)]+?)(?:\\*\\*|\\])?" +
            "(?::\\s*STATUS:\\s*\\[((?:FAILED|PASSED))\\]|:)?\\s*" +
            "(?:\\n|\\r\\n)\\s*(?:-|\\*)*\\s*File:\\s*(.*?)\\s*" +
            "(?:\\n|\\r\\n)\\s*(?:-|\\*)*\\s*Lines?:\\s*([\\d,\\s-]+)\\s*" +
            "(?:\\n|\\r\\n)\\s*(?:-|\\*)*\\s*Problem:\\s*(.*?)\\s*" +
            "(?:\\n|\\r\\n)\\s*(?:-|\\*)*\\s*(?:AI\\s*)?Suggested\\s*Fix:\\s*(.*?)" +
            "(?=\\n(?:(?:\\*\\*|\\[)?[A-Za-z0-9\\s&/\\-_\\(\\)]+(?:\\*\\*|\\])?:|\\z))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

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

    private boolean isGitHubContext() {
        return Optional.ofNullable(System.getenv("GITHUB_REPOSITORY")).filter(s -> !s.isBlank()).isPresent()
                && (Optional.ofNullable(System.getenv("GITHUB_PR_NUMBER")).filter(s -> !s.isBlank()).isPresent()
                || Optional.ofNullable(System.getenv("CHANGE_ID")).filter(s -> !s.isBlank()).isPresent())
                && Optional.ofNullable(System.getenv("GITHUB_TOKEN")).filter(s -> !s.isBlank()).isPresent();
    }

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

    private String getGitHubToken() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing required GitHub token: GITHUB_TOKEN");
        }
        return token;
    }

    private String getGitHubApiBase() {
        return Optional.ofNullable(System.getenv("GITHUB_API_URL")).filter(s -> !s.isBlank()).orElse("https://api.github.com");
    }

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

    private static List<ReviewFinding> parseReviewFindings(String reviewText) {
        List<ReviewFinding> findings = new ArrayList<>();
        if (reviewText == null || reviewText.isBlank()) {
            return findings;
        }

        String normalizedText = reviewText.replaceAll("(?m)^\\s*[-*]+\\s*", "");
        Pattern blockPattern = Pattern.compile(
                "(?ms)^[ \t]*([A-Za-z0-9 _\\[\\]-]+?):?\\s*(?:STATUS:\\s*\\[(FAILED|PASSED)\\])?\\s*(.*?)(?=^[ \\\t]*[A-Za-z0-9 _\\[\\]-]+?:?\\s*(?:STATUS:|$)|\\z)",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

        Matcher blockMatcher = blockPattern.matcher(normalizedText);
        while (blockMatcher.find()) {
            String category = Optional.ofNullable(blockMatcher.group(1)).orElse("Unknown").trim();
            String status = Optional.ofNullable(blockMatcher.group(2)).orElse("").trim();
            String body = Optional.ofNullable(blockMatcher.group(3)).orElse("");

            if (status.isBlank()) {
                Matcher statusMatcher = Pattern.compile("STATUS:\\s*\\[(FAILED|PASSED)\\]", Pattern.CASE_INSENSITIVE).matcher(body);
                if (statusMatcher.find()) {
                    status = statusMatcher.group(1).toUpperCase();
                } else {
                    status = "PASSED";
                }
            }

            String file = extractSingleLineField(body, "File").orElse("");
            String lineValue = extractSingleLineField(body, "Line").orElse("0");
            int lineNumber = 0;
            try {
                String firstLineToken = lineValue.split("[,\\s-]+")[0].trim();
                lineNumber = Integer.parseInt(firstLineToken);
            } catch (Exception ignored) {
                lineNumber = 0;
            }
            String problem = extractFieldBody(body, "Problem").orElse("");
            String suggestedFix = extractFieldBody(body, "AI Suggested Fix").orElse("");

            ReviewFinding finding = new ReviewFinding();
            finding.category = category.replace("*", "").replace("[", "").replace("]", "").trim();
            finding.status = status.isBlank() ? "PASSED" : status;
            finding.file = file.trim();
            finding.line = lineNumber;
            finding.problem = problem.trim();
            finding.suggestedFix = suggestedFix.trim();
            findings.add(finding);
        }

        return findings;
    }

    private static Optional<String> extractSingleLineField(String text, String fieldName) {
        Pattern fieldPattern = Pattern.compile("(?im)^\\s*" + Pattern.quote(fieldName) + ":\\s*(.*)$");
        Matcher matcher = fieldPattern.matcher(text);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    private static Optional<String> extractFieldBody(String text, String fieldName) {
        Pattern fieldPattern = Pattern.compile("(?ims)" + Pattern.quote(fieldName) + ":\\s*(.*?)(?=^\\s*[A-Za-z0-9 _\\[\\]-]+?:\\s*|\\z)");
        Matcher matcher = fieldPattern.matcher(text);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    private void postGitHubReviewComments(List<ReviewFinding> findings) throws Exception {
        String repo = getGitHubRepository();
        String prNumber = getGitHubPullRequestNumber();
        String apiBase = getGitHubApiBase();
        String commitSha = getGitCommitSha();

        for (ReviewFinding finding : findings) {
            createGitHubPullRequestComment(repo, prNumber, apiBase, commitSha, finding);
        }
    }

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
        LOGGER.fine(() -> "Posting PR comment to: " + repo + " PR:" + prNumber + " file:" + finding.file + " line:" + finding.line);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s/comments", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + getGitHubToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private static class ReviewFinding {
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
                                "[HELP] Please ensure Ollama is installed and running via: ollama run qwen2.5-coder:7b");
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
