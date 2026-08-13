package com.ai.playwrightaihelper;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Planner contract: convert a human goal into ordered execution phases.
 */
public interface McpHelperPlanner {

    @SystemMessage("{{systemPrompt}}")
    @UserMessage("{{goal}}")
    String createPlan(@V("goal") String goal, @V("systemPrompt") String systemPrompt);
}
