package com.framework.steps;

import com.framework.core.PlaywrightFactory;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Paths;

public class Hooks {
    private static final Logger logger = LogManager.getLogger(Hooks.class);

    @Before
    public void setup(Scenario scenario) {
        logger.info("==========================================================================");
        logger.info("Starting Scenario: {}", scenario.getName());
        logger.info("==========================================================================");
        
        PlaywrightFactory.initBrowser();
        
        // Start Tracing
        PlaywrightFactory.getContext().tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
    }

    @After
    public void tearDown(Scenario scenario) {
        String safeName = scenario.getName().replaceAll("[^a-zA-Z0-9_-]", "_");
        
        if (scenario.isFailed()) {
            logger.error("Scenario FAILED: {}", scenario.getName());
            String screenshotPath = "target/screenshots/" + safeName + ".png";
            byte[] screenshot = PlaywrightFactory.getPage().screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get(screenshotPath))
                .setFullPage(true));
            scenario.attach(screenshot, "image/png", safeName);
            
            // Save Tracing
            PlaywrightFactory.getContext().tracing().stop(new Tracing.StopOptions()
                .setPath(Paths.get("target/traces/" + safeName + "-trace.zip")));
            logger.info("Screenshot and trace saved for failed scenario.");
        } else {
            logger.info("Scenario PASSED: {}", scenario.getName());
            PlaywrightFactory.getContext().tracing().stop();
        }
        
        PlaywrightFactory.quitBrowser();
        logger.info("==========================================================================");
    }
}
