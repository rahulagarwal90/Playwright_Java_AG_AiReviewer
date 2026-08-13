package com.ai.playwrightaihelper;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Healer contract: analyze a failure and produce the next corrective action.
 */
public interface McpHelperHealer {

    @SystemMessage("{{systemPrompt}}")
    @UserMessage({
            "StackTrace:",
            "{{stackTrace}}",
            "AccessibilityTree:",
            "{{accessibilityTree}}"
    })
    String healFailure(@V("stackTrace") String stackTrace,
                       @V("accessibilityTree") String accessibilityTree,
                       @V("systemPrompt") String systemPrompt);
}
