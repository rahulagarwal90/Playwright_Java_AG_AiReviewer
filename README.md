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
  - Pull and launch the reviewer model in your terminal:
    ```bash
    ollama pull qwen2.5-coder:14b
    ollama run qwen2.5-coder:14b
    ```
  - If you need a lower-memory alternative, use `qwen2.5-coder:7b` instead.
  - If the reviewer cannot connect, start the Ollama HTTP service with:
    ```bash
    ollama serve
    ```
- **Playwright Browsers**:
  - Automatically downloaded during the first test run execution.
- **Browser video recording**:
  - Controlled with `browser.recordvideo=true|false` in `playwright-tests/src/test/resources/config.properties` or via `-Dbrowser.recordvideo=false`.
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

- **Clear old Allure data before each run**:
  ```bash
  rm -rf playwright-tests/target/allure-results playwright-tests/target/screenshots playwright-tests/target/traces playwright-tests/target/videos
  ```
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

The AI Reviewer isolates your latest staged/unstaged changes using `git diff HEAD`, filters out non-code metadata, annotates added lines with destination line numbers, and sends the prepared diff to a local Ollama model for review. The normal way to run the reviewer is by launching the Java main class directly.

To compile and trigger the code reviewer:
```bash
# Compile both modules
mvn clean compile

# Run the review engine
mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
```

*Note: Make sure you have edited or staged changes in git, otherwise the engine will report `No git changes detected.`*

### What `LocalCodeReviewerTest` is doing
The file `ai-reviewer/src/test/java/com/ai/reviewer/LocalCodeReviewerTest.java` is a JUnit test suite, not the normal review runner. Its `@Test` methods verify the internal reviewer behavior, including:
- The no-changes path returns `No changes` and does not call Ollama.
- A successful mocked Ollama response is parsed correctly and returned.
- A non-200 HTTP response is raised as an exception.
- The reviewer builds the correct request payload and uses the expected endpoint.

So yes, use `mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"` to execute the AI reviewer in real usage. The `@Test` methods in `LocalCodeReviewerTest` are there to catch regressions during development and CI, not to perform the normal reviewer run.

### Demo reviewer test block
This repository also includes a hidden test fixture in `playwright-tests/src/test/java/com/framework/steps/DemoQaSteps.java` that you can use as a live AI review demo. The block is currently wrapped in `if (false)` so it does not execute during normal test runs, but the source remains available for showing the reviewer or temporarily enabling it.

---

## 4. Continuous Integration (CI/CD) Jenkins Pipeline Isolation

To prevent the CI/CD pipeline or remote build runners from attempting to connect to a local Ollama instance (which is only meant for local offline development inside VS Code):

Target only the browser automation suite in Jenkins using:
```bash
mvn -pl playwright-tests test
```

This bypasses the `ai-reviewer` execution and ensures CI/CD builds pass successfully in environments without a local model server.

---

## 5. Jenkins CI — Setup & Running the AI Code Quality Gate

This repository includes a ready `Jenkinsfile` that implements the AI Code Quality Gate using `mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"`.

Follow these steps to install, configure, and run Jenkins for the PR gate.

1) Start Jenkins (recommended: Docker)

```bash
# Recommended: run Jenkins in Docker (isolated, repeatable)
docker run --name jenkins -p 8080:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -d jenkins/jenkins:lts

# View logs and get initial admin password
docker logs -f jenkins
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Alternative installation options are Homebrew or the standalone WAR; use whichever works for your environment. See the earlier section for quick commands.

2) Install recommended Jenkins plugins

- GitHub Branch Source (for multibranch and PR detection)
- Pipeline (Declarative Pipeline support)
- Credentials (store tokens)
- GitHub (optional: for webhooks and status reporting)
- Allure (optional: reporting)

3) Configure Jenkins credentials

- Create a **Secret Text** credential containing a GitHub personal access token that has `repo:status`, `repo:pull_request`, and `pull_request_review` (or equivalent) permissions.
- Use the credential ID `github-pr-token` (or update the `Jenkinsfile` to the ID you choose).

4) Configure the repository in Jenkins

- Create a new Multibranch Pipeline job and point it at your GitHub repository. Jenkins will detect the `Jenkinsfile` in the repository root and create PR-based branches automatically.
- Ensure webhooks are registered on the GitHub repository for Pull Request opened/updated events. Alternatively, use the GitHub Branch Source plugin to manage webhooks.

5) The `Jenkinsfile` in this repo does the following:

- Checks out the PR branch
- Verifies Ollama HTTP service is reachable at `http://localhost:11434` on the agent
- Compiles the `ai-reviewer` module
- Runs the reviewer with `mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"`
- Exits with non-zero status if the reviewer detects any `[FAILED]` findings, causing Jenkins to mark the build as failed and GitHub status to be failing (blocking merge)

6) Example: run the reviewer locally (useful for testing the gate before Jenkins)

```bash
# From repository root
mvn -pl ai-reviewer -DskipTests compile
mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
```

7) Best practices and troubleshooting

- Jenkins agent must be able to reach the Ollama HTTP service. If Jenkins agents run in containers, either run Ollama inside the same network or use an accessible host URL.
- Ensure `GITHUB_REPOSITORY`, `CHANGE_ID` (PR number) and `GITHUB_TOKEN` (credential) are available in the agent environment; GitHub Branch Source plugin exposes these automatically for multibranch PR runs.
- If your Jenkins setup does not expose `GITHUB_*` env vars, update the `Jenkinsfile` to pass the repository and PR number as pipeline parameters to the run stage.
- Admins can still bypass the GitHub status check manually in GitHub if necessary (human override rule).

8) Security notes

- Keep the GitHub token in Jenkins credentials only (do not commit tokens). Use the credential ID in the `Jenkinsfile` environment mapping.
- Review and limit token scopes to the minimum required. Rotate tokens periodically.


## 🌐 Local Webhook Tunnel Setup (GitHub to Local Jenkins)

Since Jenkins runs locally (`http://localhost:8080`), external GitHub servers cannot reach it directly. Use `ngrok` to expose a secure tunnel so GitHub can trigger your AI Code Quality Gate on every Pull Request event.

### 1. Prerequisites & ngrok Installation
If you haven't installed ngrok yet on your macOS machine, use Homebrew:
```bash
brew install ngrok

Before running the tunnel, add your free personal authentication token to your machine's configuration:

ngrok config add-authtoken <your-personal-token>

Your configuration is safely saved at: /Users/rahul/Library/Application Support/ngrok/ngrok.yml
3. Launch the Secure Tunnel
Expose your local Jenkins port (8080) to the public internet:
ngrok http 8080

Keep this terminal session running. Note your public forwarding address, for example:
https://transpire-removal-unable.ngrok-free.dev

4. Configure the GitHub Webhook
	1.	Go to your repository on GitHub -> Settings -> Webhooks -> Add webhook.
	2.	Payload URL: Paste your unique forwarding URL and append /github-webhook/ explicitly to the end:
https://transpire-removal-unable.ngrok-free.dev/github-webhook/
	3.	Content type: Change from form-data to application/json.
	4.	Secret: Leave blank.
	5.	Trigger events: Select Let me select individual events -> check Pull requests (uncheck Pushes if isolating strictly to PR gates).
	6.	Click Add Webhook and refresh the page to verify a green checkmark appears.