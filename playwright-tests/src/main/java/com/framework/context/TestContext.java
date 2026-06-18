package com.framework.context;

import com.framework.core.PlaywrightFactory;
import com.microsoft.playwright.Page;

public class TestContext {
    private final Page page;

    // Shared state variables across multiple step definitions
    private String sharedItemName;
    private String sharedErrorMessage;

    public TestContext() {
        this.page = PlaywrightFactory.getPage();
    }

    public Page getPage() {
        return page;
    }

    public String getSharedItemName() {
        return sharedItemName;
    }

    public void setSharedItemName(String sharedItemName) {
        this.sharedItemName = sharedItemName;
    }

    public String getSharedErrorMessage() {
        return sharedErrorMessage;
    }

    public void setSharedErrorMessage(String sharedErrorMessage) {
        this.sharedErrorMessage = sharedErrorMessage;
    }
}
