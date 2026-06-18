package com.framework.steps.saucedemo;

import com.framework.context.TestContext;
import com.framework.pages.saucedemo.CheckoutCompletePage;
import com.framework.pages.saucedemo.CheckoutStepOnePage;
import com.framework.pages.saucedemo.CheckoutStepTwoPage;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class CheckoutSteps {

    private TestContext testContext;

    public CheckoutSteps(TestContext testContext) {
        this.testContext = testContext;
    }

    @When("I enter my shipping information {string}, {string}, and {string}")
    public void iEnterMyShippingInformation(String firstName, String lastName, String zip) {
        CheckoutStepOnePage stepOne = new CheckoutStepOnePage(testContext.getPage());
        stepOne.fillCustomerInfo(firstName, lastName, zip);
    }

    @When("I confirm my order")
    public void iConfirmMyOrder() {
        CheckoutStepTwoPage stepTwo = new CheckoutStepTwoPage(testContext.getPage());
        stepTwo.verifyItemTotalIsDisplayed();
        stepTwo.clickFinish();
    }

    @Then("I should see the order completion message")
    public void iShouldSeeTheOrderCompletionMessage() {
        CheckoutCompletePage completePage = new CheckoutCompletePage(testContext.getPage());
        completePage.verifySuccessfulOrder();
    }
}
