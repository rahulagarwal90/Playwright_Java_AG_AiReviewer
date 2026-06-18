@UI @SauceDemo
Feature: User Login Flow
  As a SauceDemo Customer
  I want to login with various credentials
  So I can access the inventory seamlessly

  @Login
  Scenario: Standard User can login successfully
    Given I navigate to SauceDemo Login
    When I attempt login with config credentials
    Then I am successfully redirected to the Inventory Page
