# Playwright Java BDD Automation & AI Reviewer Framework

A production-grade, multi-module Maven automation repository designed to separate browser automation tests from local, sandboxed offline code review utilities.

## Module Structure

- **`playwright-tests`**: Core browser automation framework containing Page Objects, step definitions, Cucumber features, configuration resources, and test runners.
- **`ai-reviewer`**: A sandboxed offline local code reviewer utility leveraging Ollama to analyze git diffs for best practices before committing code.

---

## 1. Prerequisites & Setup

To set up this framework on your local system:

- **Java JDK**: 17 or 21 (OpenJDK 21 recommended)
  - Verify: `java -version`
- **Maven**: 3.9+
  - Verify: `mvn -version`
- **Ollama (for local AI Reviewer)**:
  - Download and install [Ollama for Mac](https://ollama.com/download/mac).
  - Pull and launch the qwen2.5-coder model in your terminal:
    ```bash
    ollama run qwen2.5-coder:7b
    ```
- **Playwright Browsers**:
  - Automatically downloaded during the first test run execution.
- **IDE Recommendations**:
  - **VS Code**: Install `Extension Pack for Java` and `Cucumber (Gherkin)` extensions.
  - **IntelliJ IDEA**: Install the `Cucumber for Java` plugin.

---

## 2. Browser Automation (`playwright-tests`)

### Standard Test Execution (Default 2 Workers)
```bash
mvn clean test -pl playwright-tests
```

### Configure Parallel Workers
```bash
mvn clean test -pl playwright-tests -Dworkers=4
```

### Run Specific Tagged Scenarios
```bash
mvn clean test -pl playwright-tests -D"cucumber.filter.tags=@SauceDemo"
```

### Viewing The Reports

- **Allure Report (Generate & Open)**:
  ```bash
  mvn allure:serve -pl playwright-tests
  ```
- **Cucumber HTML Report**:
  - Open `playwright-tests/target/cucumber-reports.html` directly in your web browser.
- **Surefire Reports (Raw XML)**:
  - Location: `playwright-tests/target/surefire-reports/`

### Artifact Collection
- **Execution Videos**: `playwright-tests/target/videos/`
- **Failure Screenshots**: `playwright-tests/target/screenshots/`
- **Playwright Traces**: `playwright-tests/target/traces/` (Upload and view on [trace.playwright.dev](https://trace.playwright.dev)).

---

## 3. Running the Automated Local AI Code Reviewer (`ai-reviewer`)

The AI Reviewer isolates your latest staged/unstaged changes using `git diff HEAD`, strips out file rename noise, and runs a diagnostic code review using local LLM `qwen2.5-coder:7b`.

To compile and trigger the code reviewer:
```bash
# Compile both modules
mvn clean compile

# Run the review engine
mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
```

*Note: Make sure you have edited or staged changes in git, otherwise the engine will report `No git changes detected.`*

---

## 4. Continuous Integration (CI/CD) Jenkins Pipeline Isolation

To prevent the CI/CD pipeline or remote build runners from attempting to connect to a local Ollama instance (which is only meant for local offline development inside VS Code):

Target only the browser automation suite in Jenkins using:
```bash
mvn -pl playwright-tests test
```

This bypasses the `ai-reviewer` execution and ensures CI/CD builds pass successfully in environments without a local model server.
