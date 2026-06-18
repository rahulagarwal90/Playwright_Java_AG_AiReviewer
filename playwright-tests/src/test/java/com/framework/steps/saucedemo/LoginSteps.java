package com.framework.steps.saucedemo;

import com.framework.context.TestContext;
import com.framework.pages.saucedemo.InventoryPage;
import com.framework.pages.saucedemo.LoginPage;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class LoginSteps {

    private LoginPage loginPage;
    private InventoryPage inventoryPage;

    // PicoContainer Dependency Injection seamlessly injects the TestContext
    public LoginSteps(TestContext testContext) {
        this.loginPage = new LoginPage(testContext.getPage());
    }

    @Given("I navigate to SauceDemo Login")
    public void iNavigateToSauceDemoLogin() {
        loginPage.navigateToLogin();
    }

    @When("I attempt login with config credentials")
    public void iAttemptLoginWithConfigCredentials() {
        inventoryPage = loginPage.loginWithConfigCredentials();
    }

    @Then("I am successfully redirected to the Inventory Page")
    public void iAmSuccessfullyRedirectedToTheInventoryPage() {
        inventoryPage.verifySuccessfulLogin();
    }
}
