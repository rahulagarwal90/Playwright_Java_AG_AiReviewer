package com.framework.pages.saucedemo;

import com.framework.pages.BasePage;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

public class CartPage extends BasePage {

    // Locators
    private final String checkoutButton = "[data-test='checkout']";
    private final String cartItemName = ".inventory_item_name";

    public CartPage(Page page) {
        super(page);
    }

    @Step("Verifying item '{expectedProductName}' is in the cart")
    public void verifyItemInCart(String expectedProductName) {
        logger.info("Verifying item in cart: {}", expectedProductName);
        assertThat(page.locator(cartItemName).first()).hasText(expectedProductName);
    }

    @Step("Clicking on checkout button")
    public CheckoutStepOnePage clickCheckout() {
        click(checkoutButton);
        return new CheckoutStepOnePage(page);
    }
}
