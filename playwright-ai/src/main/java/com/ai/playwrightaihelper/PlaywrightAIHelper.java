package com.ai.playwrightaihelper;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Lean orchestrator that keeps planner, generator, and healer isolated.
 */
public class PlaywrightAIHelper implements AutoCloseable {

    private final McpHelperPlanner planner;
    private final McpHelperGenerator generator;
    private final McpHelperHealer healer;
    private final McpClient mcpClient;
    private final Set<String> allowedIntents;
    private final String phaseSeparator;
    private final boolean enforceToolIntents;
    private final String fallbackToolIntent;
    private final String multiIntentPolicy;
    private final List<McpHelperConfig.ObservableRewriteRule> observableRewriteRules;

    public PlaywrightAIHelper() {
        ChatLanguageModel chatModel = McpHelperConfig.chatModel();
        this.mcpClient = McpHelperConfig.playwrightClient();
        ToolProvider toolProvider = McpHelperConfig.playwrightToolProvider(mcpClient);
        this.allowedIntents = new HashSet<>(McpHelperConfig.plannerAllowedToolIntents());
        this.phaseSeparator = McpHelperConfig.plannerPhaseSeparator();
        this.enforceToolIntents = McpHelperConfig.plannerEnforceToolIntents();
        this.fallbackToolIntent = McpHelperConfig.plannerFallbackToolIntent();
        this.multiIntentPolicy = McpHelperConfig.plannerMultiIntentPolicy();
        this.observableRewriteRules = McpHelperConfig.plannerObservableRewriteRules();

        this.planner = AiServices.create(McpHelperPlanner.class, chatModel);
        this.generator = AiServices.builder(McpHelperGenerator.class)
                .chatLanguageModel(chatModel)
                .toolProvider(toolProvider)
                .build();

        this.healer = AiServices.create(McpHelperHealer.class, chatModel);
    }

    public String plan(String goal) {
        if (goal == null || goal.isBlank()) {
            return "Goal: INVALID";
        }
        String rawPlan = planner.createPlan(goal.trim(), McpHelperConfig.plannerSystemPrompt());
        return normalizePlan(rawPlan);
    }

    public String executePhase(String planText, String phaseText) {
        if (planText == null || planText.isBlank()) {
            throw new IllegalArgumentException("Plan text cannot be blank.");
        }
        if (phaseText == null || phaseText.isBlank()) {
            throw new IllegalArgumentException("Phase text cannot be blank.");
        }
        return generator.executePhase(planText.trim(), phaseText.trim(), McpHelperConfig.generatorSystemPrompt());
    }

    public String healFailure(String stackTrace, String accessibilityTree) {
        if ((stackTrace == null || stackTrace.isBlank()) && (accessibilityTree == null || accessibilityTree.isBlank())) {
            throw new IllegalArgumentException("At least one of stackTrace or accessibilityTree must be provided.");
        }
        String safeStack = stackTrace == null ? "" : stackTrace.trim();
        String safeTree = accessibilityTree == null ? "" : accessibilityTree.trim();
        return healer.healFailure(safeStack, safeTree, McpHelperConfig.healerSystemPrompt());
    }

    public int phaseCount(String planText) {
        int count = 0;
        while (true) {
            count++;
            if (getPhase(planText, count).isBlank()) {
                return count - 1;
            }
        }
    }

    public String getPhase(String planText, int phaseNumber) {
        if (planText == null || planText.isBlank() || phaseNumber < 1) {
            return "";
        }

        String startToken = "Phase: " + phaseNumber;
        String nextToken = "Phase: " + (phaseNumber + 1);
        int start = planText.indexOf(startToken);
        if (start < 0) {
            return "";
        }

        int end = planText.indexOf(nextToken, start + 1);
        if (end < 0) {
            end = planText.length();
        }
        String phase = planText.substring(start, end).trim();
        String trailingSeparator = "\n" + phaseSeparator;
        if (phase.endsWith(trailingSeparator)) {
            phase = phase.substring(0, phase.length() - trailingSeparator.length()).trim();
        } else if (phase.endsWith(phaseSeparator)) {
            phase = phase.substring(0, phase.length() - phaseSeparator.length()).trim();
        }
        return phase;
    }

    @Override
    public void close() {
        try {
            mcpClient.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to close MCP client cleanly", e);
        }
    }

    private String normalizePlan(String rawPlan) {
        if (rawPlan == null || rawPlan.isBlank()) {
            return "Goal: INVALID";
        }

        String[] lines = rawPlan.split("\\R");
        String goal = "Goal: INVALID";
        List<PlanPhase> phases = new ArrayList<>();
        PlanPhase current = null;

        for (String line : lines) {
            String trimmed = trimRight(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("Goal:") && "Goal: INVALID".equals(goal)) {
                goal = trimmed;
                continue;
            }
            if (trimmed.startsWith("Phase:")) {
                if (current != null) {
                    phases.add(current);
                }
                current = new PlanPhase();
                continue;
            }
            if (current == null) {
                continue;
            }
            if (trimmed.startsWith("Objective:")) {
                current.objective = "Objective: " + truncateWords(contentAfter(trimmed, "Objective:"), McpHelperConfig.plannerWordLimit());
            } else if (trimmed.startsWith("Expected Observable:")) {
                String observable = truncateWords(contentAfter(trimmed, "Expected Observable:"), McpHelperConfig.plannerWordLimit());
                current.observable = "Expected Observable: " + applyObservableRewrites(observable);
            } else if (trimmed.startsWith("Tool Intent:")) {
                current.intent = normalizeIntent(contentAfter(trimmed, "Tool Intent:"));
            }
        }

        if (current != null) {
            phases.add(current);
        }

        List<PlanPhase> valid = new ArrayList<>();
        for (PlanPhase phase : phases) {
            if (phase.isValid()) {
                valid.add(phase);
            }
            if (valid.size() >= McpHelperConfig.plannerMaxPhases()) {
                break;
            }
        }

        if ("Goal: INVALID".equals(goal) || valid.size() < McpHelperConfig.plannerMinPhases()) {
            return "Goal: INVALID";
        }

        StringBuilder normalized = new StringBuilder();
        normalized.append(goal).append('\n');
        normalized.append(phaseSeparator).append('\n');
        for (int i = 0; i < valid.size(); i++) {
            PlanPhase phase = valid.get(i);
            normalized.append("Phase: ").append(i + 1).append('\n');
            normalized.append(phase.objective).append('\n');
            normalized.append(phase.observable).append('\n');
            normalized.append("Tool Intent: ").append(phase.intent).append('\n');
            normalized.append(phaseSeparator).append('\n');
        }
        return normalized.toString().trim();
    }

    private String normalizeIntent(String rawIntent) {
        if (rawIntent == null || rawIntent.isBlank()) {
            return fallbackIntentOrNull();
        }
        String[] candidates = rawIntent.split("[^A-Za-z_]+");
        List<String> valid = new ArrayList<>();
        for (String candidate : candidates) {
            String token = candidate.trim().toUpperCase();
            if (!token.isEmpty() && (!enforceToolIntents || allowedIntents.contains(token))) {
                valid.add(token);
            }
        }

        if (valid.isEmpty()) {
            return fallbackIntentOrNull();
        }

        if (valid.size() > 1 && "REJECT".equals(multiIntentPolicy)) {
            return null;
        }

        return valid.get(0);
    }

    private String fallbackIntentOrNull() {
        if (!enforceToolIntents) {
            return fallbackToolIntent;
        }
        return allowedIntents.contains(fallbackToolIntent) ? fallbackToolIntent : null;
    }

    private String applyObservableRewrites(String observable) {
        String value = observable;
        for (McpHelperConfig.ObservableRewriteRule rule : observableRewriteRules) {
            Matcher matcher = rule.pattern().matcher(value);
            value = matcher.replaceAll(rule.replacement());
        }
        return value;
    }

    private String contentAfter(String line, String prefix) {
        if (line.length() <= prefix.length()) {
            return "";
        }
        return line.substring(prefix.length()).trim();
    }

    private String truncateWords(String text, int maxWords) {
        String[] words = text.trim().split("\\s+");
        if (words.length <= maxWords) {
            return text.trim();
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(words[i]);
        }
        return out.toString();
    }

    private String trimRight(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static class PlanPhase {
        String objective;
        String observable;
        String intent;

        boolean isValid() {
            return objective != null && !objective.isBlank()
                    && observable != null && !observable.isBlank()
                    && intent != null && !intent.isBlank();
        }
    }
}
