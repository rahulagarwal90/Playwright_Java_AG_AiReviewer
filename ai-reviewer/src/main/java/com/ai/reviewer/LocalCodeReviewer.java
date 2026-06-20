package com.ai.reviewer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LocalCodeReviewer {

    private final HttpClient httpClient;

    public LocalCodeReviewer() {
        this(HttpClient.newHttpClient());
    }

    public LocalCodeReviewer(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public static void main(String[] args) {
        LocalCodeReviewer reviewer = new LocalCodeReviewer();
        try {
            reviewer.runReview().join();
        } catch (Exception e) {
            System.err.println("[ERROR] Exception during execution: " + e.getMessage());
        }
    }

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

    public String getGitDiff() throws Exception {
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
                .uri(URI.create("http://localhost:11434/api/chat"))
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
                    String feedback = jsonResponse.getAsJsonObject("message").get("content").getAsString();
                    System.out.println("\n==================================================");
                    System.out.println("                AI CODE REVIEW FEEDBACK           ");
                    System.out.println("==================================================");
                    System.out.println(feedback);
                    System.out.println("==================================================");
                    return feedback;
                });
    }
}
