package com.ai.playwrightaihelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Interactive CLI for one-command Planner, Generator, and Healer usage.
 */
public class PlaywrightAIHelperCli {

    // Extracts locator payload from logs like: locator("#userEmail") and keeps escaped content intact.
    private static final Pattern FAILED_LOCATOR_PAYLOAD_PATTERN = Pattern.compile("locator\\((\"(?:\\\\.|[^\\\"])*\"|'(?:\\\\.|[^'])*')\\)");
    private static final Pattern WAITING_FOR_QUOTED_PATTERN = Pattern.compile("waiting for [^\\n]*?[\"']((?:#|//|\\.//|xpath=|css=|text=)[^\"'\\n]+)[\"']");
    private static final Pattern WAITING_FOR_RAW_PATTERN = Pattern.compile("waiting for\\s+((?:#|//|\\.//|xpath=|css=|text=)[^\\n\\r]+)");
    // Pulls id values from accessibility/context snippets and normalizes them into #id format.
    private static final Pattern CONTEXT_ID_PATTERN = Pattern.compile("\\bid\\s*[:=]\\s*[\"']?([A-Za-z][A-Za-z0-9_-]*)");
    // Captures Java string literals so source-locator discovery is not limited to #id patterns.
    private static final Pattern JAVA_STRING_LITERAL_PATTERN = Pattern.compile("\"((?:\\\\.|[^\\\"])*)\"");
    // Extracts locator-like fragments from healer text output (id/css/xpath/text selectors).
    private static final Pattern LOCATOR_FRAGMENT_PATTERN = Pattern.compile("(#[A-Za-z][A-Za-z0-9_-]*|\\.//[^\\s`\"']+|//[^\\s`\"']+|xpath=[^\\s`\"']+|css=[^\\s`\"']+|text=[^\\n`]+)");
    private static final Pattern PAGE_CLASS_IN_STACK_PATTERN = Pattern.compile("at\\s+com\\.framework\\.pages(?:\\.[A-Za-z0-9_]+)*\\.([A-Za-z0-9_]+)\\.");
    private static final Pattern CSS_ID_TOKEN_PATTERN = Pattern.compile("#([A-Za-z][A-Za-z0-9_-]*)");
    private static final Pattern XPATH_ID_TOKEN_PATTERN = Pattern.compile("@id\\s*=\\s*([\"'])([A-Za-z][A-Za-z0-9_-]*)\\1");

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0].trim().toLowerCase() : "";
        int exitCode = 0;
        try (Scanner scanner = new Scanner(System.in); PlaywrightAIHelper helper = new PlaywrightAIHelper()) {
            if (mode.isBlank()) {
                System.out.println("Select mode: planner | generator | healer");
                mode = scanner.nextLine().trim().toLowerCase();
            }

            switch (mode) {
                case "planner" -> runPlanner(helper, scanner);
                case "generator" -> runGenerator(helper, scanner);
                case "healer" -> exitCode = runHealer(helper) ? 0 : 1;
                default -> {
                    System.out.println("Unsupported mode. Use planner, generator, or healer.");
                    exitCode = 2;
                }
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            exitCode = 1;
        }

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void runPlanner(PlaywrightAIHelper helper, Scanner scanner) {
        while (true) {
            System.out.println("Enter goal (or type EXIT):");
            String goal = scanner.nextLine().trim();
            if ("EXIT".equalsIgnoreCase(goal)) {
                break;
            }
            try {
                String plan = helper.plan(goal);
                System.out.println("\n=== PLAN ===");
                System.out.println(plan);
            } catch (Exception e) {
                System.err.println("Planner error: " + e.getMessage());
            }
        }
    }

    private static void runGenerator(PlaywrightAIHelper helper, Scanner scanner) {
        while (true) {
            System.out.println("Enter goal (or type EXIT):");
            String goal = scanner.nextLine().trim();
            if ("EXIT".equalsIgnoreCase(goal)) {
                break;
            }

            String plan;
            try {
                plan = helper.plan(goal);
                System.out.println("\n=== PLAN ===");
                System.out.println(plan);
            } catch (Exception e) {
                System.err.println("Planner error: " + e.getMessage());
                continue;
            }

            while (true) {
                int phaseCount = helper.phaseCount(plan);
                if (phaseCount == 0) {
                    System.out.println("No executable phases found.");
                    break;
                }

                System.out.println("Enter phase number to execute, ALL, NEWGOAL, or EXIT:");
                String choice = scanner.nextLine().trim();

                if ("EXIT".equalsIgnoreCase(choice)) {
                    return;
                }
                if ("NEWGOAL".equalsIgnoreCase(choice)) {
                    break;
                }

                if ("ALL".equalsIgnoreCase(choice)) {
                    for (int i = 1; i <= phaseCount; i++) {
                        String phaseText = helper.getPhase(plan, i);
                        if (phaseText.isBlank()) {
                            continue;
                        }
                        System.out.println("\n=== EXECUTING PHASE " + i + " ===");
                        try {
                            String result = helper.executePhase(plan, phaseText);
                            System.out.println(result);
                        } catch (Exception e) {
                            System.err.println("Generator error in phase " + i + ": " + e.getMessage());
                        }
                    }
                    continue;
                }

                try {
                    int phaseNumber = Integer.parseInt(choice);
                    String phaseText = helper.getPhase(plan, phaseNumber);
                    if (phaseText.isBlank()) {
                        System.out.println("Phase not found: " + phaseNumber);
                        continue;
                    }
                    System.out.println("\n=== PHASE TO EXECUTE ===");
                    System.out.println(phaseText);
                    String result = helper.executePhase(plan, phaseText);
                    System.out.println("\n=== EXECUTION RESULT ===");
                    System.out.println(result);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid input. Use a number, ALL, NEWGOAL, or EXIT.");
                } catch (Exception e) {
                    System.err.println("Generator error: " + e.getMessage());
                }
            }
        }
    }

    private static boolean runHealer(PlaywrightAIHelper helper) {
        String autoStack = autoLoadLatestFailure();
        if (autoStack.isBlank()) {
            System.err.println("No failure report found. Run tests first or set PLAYWRIGHT_AI_HEALER_REPORT_FILE.");
            return false;
        }
        String autoContext = autoLoadAccessibilityContext();
        try {
            String result = helper.healFailure(autoStack, autoContext);
            System.out.println("\n=== HEALER RESULT (AUTO) ===");
            System.out.println(result);

            boolean fixed = applyAutoFix(autoStack, autoContext, result);
            if (!fixed) {
                System.err.println("AUTO-FIX could not apply a safe patch.");
                return false;
            }
            System.out.println("AUTO-FIX applied successfully.");

            return true;
        } catch (Exception e) {
            System.err.println("Healer error: " + e.getMessage());
            return false;
        }
    }

    private static boolean applyAutoFix(String failureLog, String accessibilityContext, String healerOutput) {
        String brokenLocator = extractBrokenLocator(failureLog);
        if (brokenLocator.isBlank()) {
            return false;
        }

        Set<String> contextLocators = collectContextLocators(accessibilityContext);
        Set<String> sourceLocators = collectSourceLocators();

        // Try context-first (highest confidence), then source literals, then healer suggestion if it maps to source.
        String suggestedLocator = findBestCandidateInSet(contextLocators, brokenLocator);

        if (suggestedLocator.isBlank()) {
            suggestedLocator = findBestCandidateInSet(sourceLocators, brokenLocator);
        }

        if (suggestedLocator.isBlank()) {
            suggestedLocator = suggestComposedLocatorFromContextOrSource(brokenLocator, contextLocators, sourceLocators);
        }

        if (suggestedLocator.isBlank()) {
            String fromHealer = extractSuggestedLocator(healerOutput, brokenLocator);
            if (isSafeHealerCandidate(fromHealer, brokenLocator, contextLocators, sourceLocators)) {
                suggestedLocator = fromHealer;
            }
        }

        suggestedLocator = normalizeSelectorEngine(suggestedLocator, brokenLocator);

        if (!contextLocators.isEmpty() && !isCompatibleWithContext(suggestedLocator, contextLocators)) {
            return false;
        }

        if (suggestedLocator.isBlank() || suggestedLocator.equals(brokenLocator)) {
            return false;
        }

        Set<Path> candidateFiles = extractCandidateFilesFromFailureLog(failureLog);
        int changedFiles = replaceLocatorInSources(brokenLocator, suggestedLocator, candidateFiles);
        if (changedFiles <= 0) {
            return false;
        }

        System.out.println("Updated locator " + brokenLocator + " -> " + suggestedLocator + " in " + changedFiles + " file(s).");
        return true;
    }

    private static String suggestComposedLocatorFromContextOrSource(String brokenLocator,
                                                                     Set<String> contextLocators,
                                                                     Set<String> sourceLocators) {
        String repaired = repairCssIdToken(brokenLocator, contextLocators);
        if (repaired.isBlank()) {
            repaired = repairCssIdToken(brokenLocator, sourceLocators);
        }
        if (!repaired.isBlank()) {
            return repaired;
        }

        repaired = repairXpathIdToken(brokenLocator, contextLocators);
        if (repaired.isBlank()) {
            repaired = repairXpathIdToken(brokenLocator, sourceLocators);
        }
        return repaired;
    }

    private static String repairCssIdToken(String brokenLocator, Set<String> candidates) {
        if (brokenLocator == null || brokenLocator.isBlank()) {
            return "";
        }
        Matcher matcher = CSS_ID_TOKEN_PATTERN.matcher(brokenLocator);
        if (!matcher.find()) {
            return "";
        }

        String brokenId = matcher.group(1);
        String bestIdSelector = findBestCandidateInSet(candidates, "#" + brokenId);
        if (bestIdSelector.isBlank() || !bestIdSelector.startsWith("#")) {
            return "";
        }

        String fixedId = bestIdSelector.substring(1);
        if (fixedId.equals(brokenId)) {
            return "";
        }

        return brokenLocator.substring(0, matcher.start(1)) + fixedId + brokenLocator.substring(matcher.end(1));
    }

    private static String repairXpathIdToken(String brokenLocator, Set<String> candidates) {
        if (brokenLocator == null || brokenLocator.isBlank()) {
            return "";
        }

        Matcher matcher = XPATH_ID_TOKEN_PATTERN.matcher(brokenLocator);
        if (!matcher.find()) {
            return "";
        }

        String quote = matcher.group(1);
        String brokenId = matcher.group(2);
        String bestIdSelector = findBestCandidateInSet(candidates, "#" + brokenId);
        if (bestIdSelector.isBlank() || !bestIdSelector.startsWith("#")) {
            return "";
        }

        String fixedId = bestIdSelector.substring(1);
        if (fixedId.equals(brokenId)) {
            return "";
        }

        String replacement = "@id=" + quote + fixedId + quote;
        return brokenLocator.substring(0, matcher.start()) + replacement + brokenLocator.substring(matcher.end());
    }

    private static String extractBrokenLocator(String failureLog) {
        String locator = extractLastLocatorPayload(FAILED_LOCATOR_PAYLOAD_PATTERN, failureLog);
        if (!locator.isBlank()) {
            return locator;
        }

        locator = extractLastGroupMatch(WAITING_FOR_QUOTED_PATTERN, failureLog, 1);
        if (!locator.isBlank()) {
            return locator;
        }

        return extractBrokenLocatorFromWaitingRawLine(failureLog);
    }

    private static String normalizeSelectorEngine(String suggestedLocator, String brokenLocator) {
        if (suggestedLocator == null || suggestedLocator.isBlank()) {
            return "";
        }
        if (looksLikeRawXpath(suggestedLocator)) {
            return "xpath=" + suggestedLocator;
        }
        if (looksLikeRawXpath(brokenLocator) && suggestedLocator.startsWith("#")) {
            return "xpath=//*[@id=\"" + suggestedLocator.substring(1) + "\"]";
        }
        return suggestedLocator;
    }

    private static boolean looksLikeRawXpath(String locator) {
        if (locator == null) {
            return false;
        }
        return (locator.startsWith("//") || locator.startsWith(".//"))
                && !locator.startsWith("xpath=");
    }

    private static String extractLastLocatorPayload(Pattern pattern, String text) {
        if (pattern == null || text == null || text.isBlank()) {
            return "";
        }

        Matcher matcher = pattern.matcher(text);
        String value = "";
        while (matcher.find()) {
            value = normalizeLocatorFromLog(matcher.group(1));
        }
        return value;
    }

    private static String extractLastGroupMatch(Pattern pattern, String text, int group) {
        if (pattern == null || text == null || text.isBlank()) {
            return "";
        }

        Matcher matcher = pattern.matcher(text);
        String value = "";
        while (matcher.find()) {
            value = normalizeLocatorFromLog(matcher.group(group));
        }
        return value;
    }

    private static String extractBrokenLocatorFromWaitingRawLine(String failureLog) {
        String locator = extractLastGroupMatch(WAITING_FOR_RAW_PATTERN, failureLog, 1);
        if (locator.isBlank()) {
            return "";
        }

        // Keep only the selector token for lines like: waiting for .//input[@id="x"]
        int newline = locator.indexOf('\n');
        if (newline >= 0) {
            locator = locator.substring(0, newline);
        }
        return normalizeLocatorFromLog(locator);
    }

    private static String normalizeLocatorFromLog(String raw) {
        if (raw == null) {
            return "";
        }

        String normalized = raw.trim()
                .replace("\\\"", "\"")
                .replace("\\'", "'");

        // Remove wrapping quotes that can appear after regex extraction from some log formats.
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        return normalized;
    }

    private static String extractSuggestedLocator(String healerOutput, String brokenLocator) {
        return findBestLocatorCandidate(healerOutput == null ? "" : healerOutput, brokenLocator);
    }

    private static String findBestCandidateInSet(Set<String> locators, String brokenLocator) {
        String prefixBest = "";
        int prefixBestDistance = Integer.MAX_VALUE;
        int prefixBestPrefix = Integer.MIN_VALUE;
        for (String locator : locators) {
            if (locator.equals(brokenLocator)) {
                continue;
            }
            if (locator.startsWith(brokenLocator) || brokenLocator.startsWith(locator)) {
                int distance = Math.abs(locator.length() - brokenLocator.length());
                int prefix = commonPrefixLength(locator, brokenLocator);
                if (distance < prefixBestDistance || (distance == prefixBestDistance && prefix > prefixBestPrefix)) {
                    prefixBest = locator;
                    prefixBestDistance = distance;
                    prefixBestPrefix = prefix;
                }
            }
        }
        if (!prefixBest.isBlank()) {
            return prefixBest;
        }

        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        int bestPrefix = Integer.MIN_VALUE;
        for (String locator : locators) {
            if (locator.equals(brokenLocator)) {
                continue;
            }
            int distance = levenshteinDistance(brokenLocator, locator);
            if (distance > 2) {
                continue;
            }
            int prefix = commonPrefixLength(brokenLocator, locator);
            if (distance < bestDistance || (distance == bestDistance && prefix > bestPrefix)) {
                best = locator;
                bestDistance = distance;
                bestPrefix = prefix;
            }
        }
        return best;
    }

    private static Set<String> collectContextLocators(String accessibilityContext) {
        Set<String> locators = new HashSet<>();
        if (accessibilityContext == null || accessibilityContext.isBlank()) {
            return locators;
        }

        Matcher idMatcher = CONTEXT_ID_PATTERN.matcher(accessibilityContext);
        while (idMatcher.find()) {
            locators.add("#" + idMatcher.group(1));
        }

        return locators;
    }

    private static Set<String> collectSourceLocators() {
        Path sourceRoot = Path.of("playwright-tests", "src", "main", "java");
        Set<String> locators = new HashSet<>();
        if (!Files.isDirectory(sourceRoot)) {
            return locators;
        }

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<Path> javaFiles = paths
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();

            for (Path file : javaFiles) {
                String content = safeReadFile(file);
                Matcher matcher = JAVA_STRING_LITERAL_PATTERN.matcher(content);
                while (matcher.find()) {
                    String literal = matcher.group(1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                            .trim();
                    if (isLocatorLike(literal)) {
                        locators.add(literal);
                    }
                }
            }
        } catch (IOException e) {
            return locators;
        }

        return locators;
    }

    private static String findBestLocatorCandidate(String text, String brokenLocator) {
        Matcher matcher = LOCATOR_FRAGMENT_PATTERN.matcher(text == null ? "" : text);
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        int bestPrefix = Integer.MIN_VALUE;
        while (matcher.find()) {
            String candidate = matcher.group().trim();
            if (candidate.equals(brokenLocator)) {
                continue;
            }

            int distance = levenshteinDistance(brokenLocator, candidate);
            if (distance > 2) {
                continue;
            }

            int prefix = commonPrefixLength(brokenLocator, candidate);
            if (distance < bestDistance || (distance == bestDistance && prefix > bestPrefix)) {
                best = candidate;
                bestDistance = distance;
                bestPrefix = prefix;
            }
        }

        return best;
    }

    private static boolean isSafeHealerCandidate(String candidate,
                                                 String brokenLocator,
                                                 Set<String> contextLocators,
                                                 Set<String> sourceLocators) {
        if (candidate == null || candidate.isBlank() || candidate.equals(brokenLocator)) {
            return false;
        }

        // Keep healer-driven replacements bounded to typo-like changes.
        if (levenshteinDistance(brokenLocator, candidate) > 2) {
            return false;
        }

        if (sourceLocators.contains(candidate)) {
            return true;
        }

        return isCompatibleWithContext(candidate, contextLocators);
    }

    private static boolean isCompatibleWithContext(String locator, Set<String> contextLocators) {
        if (locator == null || locator.isBlank()) {
            return false;
        }
        if (contextLocators == null || contextLocators.isEmpty()) {
            return true;
        }
        if (contextLocators.contains(locator)) {
            return true;
        }

        for (String contextLocator : contextLocators) {
            if (locator.contains(contextLocator)) {
                return true;
            }

            String contextId = contextLocator.startsWith("#") ? contextLocator.substring(1) : contextLocator;
            if (locator.contains("@id=\"" + contextId + "\"") || locator.contains("@id='" + contextId + "'")) {
                return true;
            }
        }

        return false;
    }

    private static boolean isLocatorLike(String value) {
        if (value == null) {
            return false;
        }
        String locator = value.trim();
        if (locator.length() < 2) {
            return false;
        }

        return locator.startsWith("#")
                || locator.startsWith(".")
                || locator.startsWith("[")
                || locator.startsWith("//")
                || locator.startsWith(".//")
                || locator.startsWith("xpath=")
                || locator.startsWith("css=")
                || locator.startsWith("text=")
                || locator.contains(">>");
    }

    private static int replaceLocatorInSources(String oldLocator, String newLocator, Set<Path> candidateFiles) {
        Path sourceRoot = Path.of("playwright-tests", "src", "main", "java");
        if (!Files.isDirectory(sourceRoot)) {
            return 0;
        }

        List<String> oldVariants = locatorVariantsForReplacement(oldLocator);
        List<String> newVariants = locatorVariantsForReplacement(newLocator);
        if (oldVariants.size() != newVariants.size()) {
            return 0;
        }

        int changed = 0;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<Path> javaFiles = paths
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();

            for (Path file : javaFiles) {
                if (!candidateFiles.isEmpty() && !candidateFiles.contains(file.toAbsolutePath().normalize())) {
                    continue;
                }
                String original = safeReadFile(file);
                if (original.isBlank()) {
                    continue;
                }

                String updated = original;
                for (int i = 0; i < oldVariants.size(); i++) {
                    String oldVariant = oldVariants.get(i);
                    String newVariant = newVariants.get(i);
                    if (updated.contains(oldVariant)) {
                        updated = updated.replace(oldVariant, newVariant);
                    }

                    String oldEscaped = escapeForJavaStringLiteral(oldVariant);
                    String newEscaped = escapeForJavaStringLiteral(newVariant);
                    if (updated.contains(oldEscaped)) {
                        updated = updated.replace(oldEscaped, newEscaped);
                    }
                }
                if (!updated.equals(original)) {
                    Files.writeString(file, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                    changed++;
                }
            }
            return changed;
        } catch (IOException e) {
            return 0;
        }
    }

    private static List<String> locatorVariantsForReplacement(String locator) {
        List<String> variants = new ArrayList<>();
        if (locator == null || locator.isBlank()) {
            return variants;
        }

        variants.add(locator);

        if (locator.startsWith("xpath=")) {
            variants.add(locator.substring("xpath=".length()));
            return variants;
        }
        if (locator.startsWith("css=")) {
            variants.add(locator.substring("css=".length()));
            return variants;
        }

        if (locator.startsWith("//") || locator.startsWith(".//")) {
            variants.add("xpath=" + locator);
        } else if (locator.startsWith("#") || locator.startsWith(".") || locator.startsWith("[") || locator.contains(">") || locator.contains(" ")) {
            variants.add("css=" + locator);
        }

        return variants;
    }

    private static String escapeForJavaStringLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static Set<Path> extractCandidateFilesFromFailureLog(String failureLog) {
        Set<Path> files = new HashSet<>();
        if (failureLog == null || failureLog.isBlank()) {
            return files;
        }

        Matcher matcher = PAGE_CLASS_IN_STACK_PATTERN.matcher(failureLog);
        while (matcher.find()) {
            String className = matcher.group(1);
            Path matches = findPageClassFile(className);
            if (matches != null) {
                files.add(matches.toAbsolutePath().normalize());
            }
        }
        return files;
    }

    private static Path findPageClassFile(String className) {
        Path sourceRoot = Path.of("playwright-tests", "src", "main", "java");
        if (!Files.isDirectory(sourceRoot)) {
            return null;
        }

        String targetFile = className + ".java";
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            Optional<Path> file = paths
                    .filter(path -> path.getFileName().toString().equals(targetFile))
                    .findFirst();
            return file.orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static int levenshteinDistance(String left, String right) {
        int[][] dp = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= right.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[left.length()][right.length()];
    }

    private static int commonPrefixLength(String left, String right) {
        int commonPrefix = 0;
        int maxPrefix = Math.min(left.length(), right.length());
        while (commonPrefix < maxPrefix && left.charAt(commonPrefix) == right.charAt(commonPrefix)) {
            commonPrefix++;
        }
        return commonPrefix;
    }

    private static String autoLoadLatestFailure() {
        String configured = System.getenv("PLAYWRIGHT_AI_HEALER_REPORT_FILE");
        if (configured != null && !configured.isBlank()) {
            return safeReadFile(Path.of(configured));
        }

        Path reportsDir = Path.of("playwright-tests", "target", "surefire-reports");
        if (!Files.isDirectory(reportsDir)) {
            return "";
        }

        try (Stream<Path> files = Files.list(reportsDir)) {
            Optional<Path> newest = files
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .max(Comparator.comparingLong(PlaywrightAIHelperCli::lastModifiedSafe));
            return newest.map(PlaywrightAIHelperCli::safeReadFile).orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    private static String autoLoadAccessibilityContext() {
        String configured = System.getenv("PLAYWRIGHT_AI_HEALER_ACCESSIBILITY_CONTEXT_FILE");
        if (configured != null && !configured.isBlank()) {
            String content = safeReadFile(Path.of(configured));
            if (!content.isBlank()) {
                return content;
            }
        }
        return "Accessibility context not provided. Source: AUTO mode.";
    }

    private static String safeReadFile(Path path) {
        try {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private static long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

}
