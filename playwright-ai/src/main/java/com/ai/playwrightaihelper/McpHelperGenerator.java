package com.ai.playwrightaihelper;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Generator contract: execute plan phases with MCP browser tools.
 */
public interface McpHelperGenerator {

    @SystemMessage("{{systemPrompt}}")
    @UserMessage({
            "Plan:",
            "{{planText}}",
            "Current Phase:",
            "{{phaseText}}"
    })
    String executePhase(@V("planText") String planText,
                        @V("phaseText") String phaseText,
                        @V("systemPrompt") String systemPrompt);
}
