package com.framework.steps.saucedemo;

import com.framework.context.TestContext;
import com.framework.pages.saucedemo.InventoryPage;
import io.cucumber.java.en.When;

public class InventorySteps {

    private InventoryPage inventoryPage;

    public InventorySteps(TestContext testContext) {
        this.inventoryPage = new InventoryPage(testContext.getPage());
    }

    @When("I add the {string} to my cart")
    public void iAddTheToMyCart(String productName) {
        inventoryPage.addProductToCart(productName);
    }

    @When("I go to the cart")
    public void iGoToTheCart() {
        inventoryPage.navigateToCart();
    }
}
