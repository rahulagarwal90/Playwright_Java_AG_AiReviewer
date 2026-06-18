package com.framework.pages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;
import io.qameta.allure.Step;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Paths;

public abstract class BasePage {
    protected final Page page;
    protected static final Logger logger = LogManager.getLogger();

    protected BasePage(Page page) {
        this.page = page;
    }

    @Step("Navigating to: {url}")
    public void navigate(String url) {
        logger.info("Navigating to URL: {}", url);
        try {
            page.navigate(url);
        } catch (Exception e) {
            logger.error("Failed to navigate to {}: {}", url, e.getMessage());
            throw e;
        }
    }

    @Step("Clicking on: {selector}")
    public void click(String selector) {
        logger.info("Clicking on element: {}", selector);
        try {
            page.click(selector);
        } catch (Exception e) {
            logger.error("Failed to click on {}: {}", selector, e.getMessage());
            throw e;
        }
    }

    @Step("Typing '{text}' into: {selector}")
    public void type(String selector, String text) {
        logger.info("Typing text '{}' into element: {}", text, selector);
        try {
            page.fill(selector, text);
        } catch (Exception e) {
            logger.error("Failed to type into {}: {}", selector, e.getMessage());
            throw e;
        }
    }

    @Step("Getting text from: {selector}")
    public String getText(String selector) {
        logger.info("Getting text from element: {}", selector);
        try {
            return page.textContent(selector).trim();
        } catch (Exception e) {
            logger.error("Failed to get text from {}: {}", selector, e.getMessage());
            throw e;
        }
    }

    @Step("Checking visibility of: {selector}")
    public boolean isVisible(String selector) {
        logger.info("Checking visibility of element: {}", selector);
        return page.isVisible(selector);
    }

    @Step("Selecting '{value}' from dropdown: {selector}")
    public void selectFromDropdown(String selector, String value) {
        logger.info("Selecting '{}' from dropdown: {}", value, selector);
        try {
            page.selectOption(selector, new SelectOption().setLabel(value));
        } catch (Exception e) {
             try {
                page.selectOption(selector, value);
             } catch (Exception e2) {
                logger.error("Failed to select '{}' from dropdown {}: {}", value, selector, e2.getMessage());
                throw e2;
             }
        }
    }

    @Step("Hovering over: {selector}")
    public void hover(String selector) {
        logger.info("Hovering over element: {}", selector);
        try {
            page.hover(selector);
        } catch (Exception e) {
            logger.error("Failed to hover over {}: {}", selector, e.getMessage());
            throw e;
        }
    }

    @Step("Taking screenshot of: {selector}")
    public void takeElementScreenshot(String selector, String path) {
        logger.info("Taking screenshot of element: {} to {}", selector, path);
        try {
            page.locator(selector).screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions().setPath(Paths.get(path)));
        } catch (Exception e) {
            logger.warn("Failed to take element screenshot: {}", e.getMessage());
        }
    }

    @Step("Waiting for element to be visible: {selector}")
    public void waitForVisible(String selector) {
        logger.info("Waiting for visibility of element: {}", selector);
        try {
            page.waitForSelector(selector);
        } catch (Exception e) {
            logger.error("Element {} did not become visible: {}", selector, e.getMessage());
            throw e;
        }
    }
}
