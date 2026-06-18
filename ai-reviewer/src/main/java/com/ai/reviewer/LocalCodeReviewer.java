package com.ai.reviewer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            System.out.println(">>> Sending changes to local Ollama (model: qwen2.5-coder:7b)...");
            return sendToOllama(filteredDiff);
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to execute git diff: " + e.getMessage());
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    public String getGitDiff() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "HEAD");
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

    public CompletableFuture<String> sendToOllama(String diffText) {
        // String Hygiene Rule: Strictly apply string sanitization on the raw diff text before payload assembly
        String sanitizedDiff = diffText
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        String systemPrompt = "You are an expert Principal QA Test Automation Architect & DevOps Engineer. " +
                "Analyze the following git diff and output a code review.\n" +
                "Review every single modified file and check for:\n" +
                "1. CRITICAL: Hardcoded wait statements, explicit sleeps, or timeouts (e.g., Thread.sleep, waitForTimeout, page.waitForTimeout).\n" +
                "2. Fragile/Brittle Locators: Absolute XPaths (e.g., starting with /html/body), index-based selectors, or hardcoded text labels instead of dynamic IDs or data-test attributes.\n" +
                "3. Hardcoded values: Credentials, URLs, API tokens, or configuration values that should be moved to properties/configuration files.\n" +
                "4. Unstructured Logging: Use of System.out.println or printStackTrace instead of professional logger classes (e.g., Log4j2).\n" +
                "5. Flaky Assertions: Assertions that do not wait for the UI/state to be ready or do not provide explicit failure messages.\n" +
                "6. Resource Leaks: Missing cleanup of browser resources, pages, contexts, or database/file connections.\n\n" +
                "Format your review clearly, listing each file and its specific issues with actionable suggestions. If a file has no issues, do not comment on it.";

        String sanitizedSystemPrompt = systemPrompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        // Assemble raw JSON manually applying the sanitization
        String rawJson = "{"
                + "\"model\":\"qwen2.5-coder:7b\","
                + "\"stream\":false,"
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + sanitizedSystemPrompt + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + sanitizedDiff + "\"}"
                + "]"
                + "}";
        // Use GSON to parse the manual JSON payload to ensure validity and format correctly
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
                        System.err.println("[HELP] Please ensure Ollama is installed and running via: ollama run qwen2.5-coder:7b");
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
