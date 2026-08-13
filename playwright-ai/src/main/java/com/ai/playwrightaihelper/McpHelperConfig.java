package com.ai.playwrightaihelper;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Minimal bootstrap for Playwright MCP over stdio and Ollama model wiring.
 */
public final class McpHelperConfig {

    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5-coder:14b";
    // Launch npx with the -y flag to prevent interactive installation prompt hangs inside the Java subprocess.
    private static final String DEFAULT_MCP_COMMAND = "npx -y @playwright/mcp@latest --headless";
    private static final String DEFAULT_CLIENT_NAME = "PlaywrightAIHelper";
    private static final String DEFAULT_CLIENT_VERSION = "1.0.0";
    private static final String DEFAULT_PHASE_SEPARATOR = "---";
    private static final String DEFAULT_TOOL_INTENTS = "NAVIGATE,CLICK,TYPE,ASSERT_VISIBLE,ASSERT_TEXT,WAIT,SELECT_OPTION,SUBMIT,BACK";

    private McpHelperConfig() {
    }

    public static ChatLanguageModel chatModel() {
        return OllamaChatModel.builder()
                .baseUrl(env("PLAYWRIGHT_AI_OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL))
                .modelName(env("PLAYWRIGHT_AI_OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL))
                .timeout(Duration.ofSeconds(envInt("PLAYWRIGHT_AI_MODEL_TIMEOUT_SECONDS", 120)))
                .build();
    }

    public static StdioMcpTransport playwrightTransport() {
        return new StdioMcpTransport.Builder()
                .command(mcpCommand())
                .logEvents(envBoolean("PLAYWRIGHT_AI_MCP_LOG_EVENTS", false))
                .build();
    }

    public static McpClient playwrightClient() {
        return new DefaultMcpClient.Builder()
                .clientName(env("PLAYWRIGHT_AI_MCP_CLIENT_NAME", DEFAULT_CLIENT_NAME))
                .clientVersion(env("PLAYWRIGHT_AI_MCP_CLIENT_VERSION", DEFAULT_CLIENT_VERSION))
                .transport(playwrightTransport())
                .toolExecutionTimeout(Duration.ofSeconds(envInt("PLAYWRIGHT_AI_MCP_TOOL_TIMEOUT_SECONDS", 120)))
                .build();
    }

    public static McpToolProvider playwrightToolProvider(McpClient client) {
        return McpToolProvider.builder()
                .mcpClients(client)
                .failIfOneServerFails(envBoolean("PLAYWRIGHT_AI_MCP_FAIL_IF_SERVER_FAILS", true))
                .build();
    }

    public static String plannerSystemPrompt() {
        String separator = plannerPhaseSeparator();
        return String.join("\n",
                "You are the Planner for PlaywrightAIHelper.",
                "Decompose the user goal into executable milestones only.",
                "Adapt phases strictly to the provided goal and do not force irrelevant steps.",
                "For any goal type, infer only the minimal relevant phases and do not pad with unrelated steps.",
                "If goal is empty, blank, or not actionable UI testing, return exactly 'Goal: INVALID' and no phases.",
                "Return " + plannerMinPhases() + " to " + plannerMaxPhases() + " phases for valid goals.",
                "Each field must be one sentence with at most " + plannerWordLimit() + " words.",
                "Tool Intent must be one of: " + String.join(", ", plannerAllowedToolIntents()) + ".",
                "You MUST use exact output template with no extra commentary.",
                "Use exactly '" + separator + "' to separate phases and end with a final separator.",
                "Goal: [Goal Summary]",
                separator,
                "Phase: 1",
                "Objective: [Objective text]",
                "Expected Observable: [Observable text]",
                "Tool Intent: [One value from Tool Intent list]",
                separator,
                "Phase: 2");
    }

    public static String generatorSystemPrompt() {
        return String.join("\n",
                "You are the Generator for PlaywrightAIHelper.",
                "Execute only the provided phase using available MCP tools.",
                "Never invent tools or skip observable checks.",
                "Return compact structured output with exactly these sections:",
                "Phase:",
                "Execution:",
                "Result: SUCCESS|FAILURE",
                "Accessibility Snapshot:");
    }

    public static String healerSystemPrompt() {
        return String.join("\n",
                "You are the Healer for PlaywrightAIHelper.",
                "Diagnose failures from stack trace and accessibility tree/context.",
                "Return concise structured output with exactly these sections:",
                "Diagnosis:",
                "Corrective Action:",
                "Retryable: true|false");
    }

    public static int plannerMinPhases() {
        return envInt("PLAYWRIGHT_AI_PLANNER_MIN_PHASES", 2);
    }

    public static int plannerMaxPhases() {
        return envInt("PLAYWRIGHT_AI_PLANNER_MAX_PHASES", 8);
    }

    public static int plannerWordLimit() {
        return envInt("PLAYWRIGHT_AI_PLANNER_WORD_LIMIT", 20);
    }

    public static String plannerPhaseSeparator() {
        return env("PLAYWRIGHT_AI_PLANNER_PHASE_SEPARATOR", DEFAULT_PHASE_SEPARATOR);
    }

    public static Set<String> plannerAllowedToolIntents() {
        String configured = env("PLAYWRIGHT_AI_PLANNER_TOOL_INTENTS", DEFAULT_TOOL_INTENTS);
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static boolean plannerEnforceToolIntents() {
        return envBoolean("PLAYWRIGHT_AI_PLANNER_ENFORCE_TOOL_INTENTS", true);
    }

    public static String plannerFallbackToolIntent() {
        return env("PLAYWRIGHT_AI_PLANNER_FALLBACK_TOOL_INTENT", "CLICK").trim().toUpperCase();
    }

    public static String plannerMultiIntentPolicy() {
        return env("PLAYWRIGHT_AI_PLANNER_MULTI_INTENT_POLICY", "REJECT").trim().toUpperCase();
    }

    public static List<ObservableRewriteRule> plannerObservableRewriteRules() {
        String raw = env("PLAYWRIGHT_AI_PLANNER_OBSERVABLE_REWRITE_RULES", "").trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(raw.split(";;"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(McpHelperConfig::toRule)
                .filter(rule -> rule != null)
                .toList();
    }

    private static ObservableRewriteRule toRule(String rawRule) {
        int marker = rawRule.indexOf("=>");
        if (marker < 0) {
            return null;
        }
        String regex = rawRule.substring(0, marker).trim();
        String replacement = rawRule.substring(marker + 2).trim();
        if (regex.isEmpty()) {
            return null;
        }
        return new ObservableRewriteRule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement);
    }

    private static List<String> mcpCommand() {
        String command = env("PLAYWRIGHT_AI_MCP_COMMAND", DEFAULT_MCP_COMMAND).trim();
        return Arrays.stream(command.split("\\s+"))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static int envInt(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    public record ObservableRewriteRule(Pattern pattern, String replacement) {
    }
}
