# Framework Architecture Documentation

This document explains the technical design and features of the Playwright Java Antigravity framework.

## 1. Core Principles
- **DRY (Don't Repeat Yourself)**: Common UI interactions are centralized in `BasePage`.
- **Thread Safety**: Uses `ThreadLocal` for Playwright instances and PicoContainer for `TestContext` injection to ensure safe parallel execution.
- **Config as Code**: Uses the **Owner** library for type-safe, centralized configuration.
- **Observability**: Integrated **Log4j2** and **Allure** for deep visibility into every test step.

## 2. Key Components

### BasePage Pattern
The `BasePage` class serves as the foundation for all Page Objects. It provides:
- Standardized methods for: `click()`, `type()`, `navigate()`, `getText()`, `selectFromDropdown()`, etc.
- **Automatic Logging**: Every interaction is logged with the selector and value.
- **Allure Integration**: Every method is marked as a `@Step` in reports.
- **Robustness**: Centralized waiting and visibility checks to reduce flakiness.

### Configuration Management (Owner Library)
We use the **Owner library** to handle our `config.properties`.

#### Before (Manual Properties):
To read a configuration value, you would normally have to do this:
```java
Properties props = new Properties();
InputStream input = new FileInputStream("config.properties");
props.load(input);
String browser = props.getProperty("browser.headless");
boolean isHeadless = Boolean.parseBoolean(browser); // Manual parsing
```

#### After (Owner Library):
Now, you just use the interface:
```java
public interface FrameworkConfig extends Config {
    @Key("browser.headless")
    boolean browserHeadless(); // Automatically parsed to boolean!
}

// Access:
FrameworkConfigManager.getConfig().browserHeadless();
```

---

### Configuration as Code (CasC)
In professional DevOps, we follow the principle of **Configuration as Code**.
- **In-Repo Config**: Settings like `browser`, `workers`, and `timeout` are stored in `config.properties`.
- **Why?**: This ensures that anyone who clones the code (including Jenkins) gets the exact same environment. It also allows you to track "who changed what" in the Git history.
- **Jenkins Role**: Jenkins should only manage **Environment Overrides** (e.g., switching from `QA` to `Staging`) and **Secrets** (see below).

### Secrets Management
Sensitive data (Passwords, GitHub Tokens, API Keys) should **never** be stored in Git.
- **The Process**: Store them in the **Jenkins Credentials Store**.
- **Implementation**: The `Jenkinsfile` then pulls these secrets and injects them as environment variables during execution. This keeps them masked (`****`) in all logs.

---

### Logging System (Log4j2)
Centralized logging via `src/main/resources/log4j2.xml`:
- **Console Appender**: Color-coded logs in the terminal.
- **File Appender**: Full history saved in `target/logs/framework.log`.
- Tracks Thread ID to distinguish between parallel scenario runs.

## 3. Reporting Architecture
The framework generates three layers of reports:
1. **Allure Report**:
   - Best for visual step-by-step analysis.
   - Shows `@Step` labels from Page Objects.
   - Attachments: Failure screenshots and traces.
2. **Cucumber HTML Report**:
   - Quick overview of pass/fail statistics.
   - Found at `target/cucumber-reports.html`.
3. **JUnit XML (Surefire)**:
   - Raw data used by Jenkins to show trend graphs.
   - Found at `target/surefire-reports/`.

---

## 4. Pull Request (PR) Strategy & Code Quality

To maintain a stable automation suite, we follow a strict branching and PR process:

1. **Feature Branching**: Every new test or fix is developed in a separate branch (e.g., `feature/demo-qa-updates`).
2. **Automated PR Triggers**:
   - Once a PR is opened, Jenkins triggers the `Jenkinsfile` pipeline.
   - Tests run in parallel (default 2 workers).
   - **Gatekeeper**: The PR cannot be merged into `main` unless all tests pass.
3. **Traceability**: Allure reports and Playwright traces are archived for every PR run to assist in debugging failed tests before they reach the main branch.

---

## 5. FAQs

### Q: Is `allure.properties` the industry standard?
**A:** Yes, it is a very common practice. 
- **Standard**: Most Allure adapters (like JUnit or Cucumber) look specifically for this file in the `resources` folder. 
- **Decoupling**: It keeps your reporting settings separate from your build tool (`pom.xml`). 
- **Alternative**: You could pass `-Dallure.results.directory=target/allure-results` every time you run the command, but using `allure.properties` is cleaner and "self-documenting."

### Q: Why do we have two Allure dependencies in the `pom.xml`?
**A:** `allure-cucumber7-jvm` is the connector that listens to Cucumber events. `allure-java-commons` is required so that our Page Objects (in `src/main/java`) can use the `@Step` annotation. Without both, the reports won't show the detailed interaction steps.

### Q: If we have Allure in the `pom.xml`, why did the `allure` command fail?
**A:** There is a difference between **generation** and **visualization**.
- The **Dependencies** in `pom.xml` generate the raw data (JSON files in `target/allure-results`).
- The **Allure CLI** (the command) is a separate tool needed to turn that raw data into a website.
- **Solution**: We added the `allure-maven-plugin` as a workaround. Now you can use `mvn allure:serve` which handles the visualization without needing to install Allure globally.

### Q: Why did `mvn clean install` run all my tests?
**A:** In the Maven lifecycle, `install` is a high-level command that includes `compile`, `test`, and `package`. To skip tests during installation, use:
```bash
mvn clean install -DskipTests
```

### Q: How do I update my project and all Maven dependencies?
**A:** Run `mvn clean install -U`. The `-U` stands for "Update" and forces Maven to check for the latest versions of your library dependencies.
