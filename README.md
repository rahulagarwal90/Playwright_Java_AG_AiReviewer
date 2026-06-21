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
  - Find installed path:
    - macOS/Linux: `which java` and `java -XshowSettings:properties -version | grep 'java.home'`
    - Windows PowerShell: `Get-Command java` and `java -XshowSettings:properties -version`
  - If using `JAVA_HOME`, confirm with `echo $JAVA_HOME` or `echo %JAVA_HOME%`.
- **Maven**: 3.9+
  - Verify: `mvn -version`
  - Find installed path:
    - macOS/Linux: `which mvn`
    - Windows PowerShell: `Get-Command mvn` or `where mvn`
  - If using `MAVEN_HOME`, confirm with `echo $MAVEN_HOME` or `echo %MAVEN_HOME%`.
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

The goal is to run the AI reviewer for every GitHub Pull Request, automatically post targeted PR comments for failed findings, and fail the Jenkins build when any review category returns `[FAILED]`.

### 5.1. Install Jenkins

> Jenkins tool configuration: this repository uses Jenkins Global Tool names `Maven 3.9` and `JDK 21` to match the `Jenkinsfile`.
> If you prefer local binary paths, install Maven and JDK on the agent and set `JAVA_HOME` and `MAVEN_HOME` accordingly.

#### Option A: Docker (recommended)
```bash
docker run --name jenkins -p 8080:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -d jenkins/jenkins:lts
```

View logs and the initial admin password:
```bash
docker logs -f jenkins
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

#### Option B: macOS Homebrew
```bash
brew install jenkins-lts
brew services start jenkins-lts
```

Stop Jenkins:
```bash
brew services stop jenkins-lts
```

#### Option C: Windows
- Download Jenkins from https://www.jenkins.io/download/
- Install the Windows package
- Launch Jenkins from the Start menu or the Services app

#### Option D: Standalone WAR
```bash
curl -LO https://get.jenkins.io/war-stable/latest/jenkins.war
java -jar jenkins.war --httpPort=8080
```

Open Jenkins in your browser at:
```text
http://localhost:8080
```

Use the initial admin password from the Jenkins startup log or from the Jenkins home directory.

### 5.2. Install required Jenkins plugins

From Jenkins Dashboard -> Manage Jenkins -> Manage Plugins -> Available, install:

- `GitHub Branch Source`
- `Pipeline`
- `Credentials`
- `GitHub`
- `Allure` (optional, for test reporting)

### 5.3. Create GitHub credentials in Jenkins

1. Go to Jenkins Dashboard -> Credentials -> System -> Global credentials.
2. Choose **Add Credentials**.
3. Use credential type **Secret text**.
4. Paste your GitHub Personal Access Token (PAT).
5. Set the credential ID to `github-pr-token`.

The token should have these minimum permissions:

- `repo:status`
- `repo:pull_request`
- `pull_request_review`

> Do not commit tokens into source control.

### 5.4. Expose your local Jenkins to GitHub using `ngrok`

Because GitHub cannot reach `http://localhost:8080` directly, use `ngrok` to expose a secure public endpoint.

#### macOS
```bash
brew install ngrok
ngrok config add-authtoken <YOUR_NGROK_AUTHTOKEN>
ngrok http 8080
```

#### Windows
```powershell
winget install ngrok
ngrok config add-authtoken <YOUR_NGROK_AUTHTOKEN>
ngrok http 8080
```

Keep the terminal running. ngrok prints a public forwarding URL such as:

```text
https://abcd-1234.ngrok-free.app
```

### 5.5. Configure the GitHub webhook

In your GitHub repository:

1. Go to **Settings** -> **Webhooks** -> **Add webhook**.
2. Set **Payload URL** to:
   ```text
   https://<your-ngrok-url>/github-webhook/
   ```
3. Set **Content type** to `application/json`.
4. Leave **Secret** empty.
5. Select **Let me select individual events**.
6. Check **Pull requests** only.
7. Click **Add webhook**.

Confirm GitHub shows a successful delivery and a green checkmark.

### 5.6. Create a Jenkins Multibranch Pipeline job

1. On Jenkins Dashboard, click **New Item**.
2. Enter a name such as `playwright-ai-reviewer`.
3. Choose **Multibranch Pipeline** and click **OK**.
4. Under **Branch Sources**, click **Add source** -> **GitHub**.
5. Add or select credentials:
   - Use the GitHub PAT credential you created earlier.
   - If using username/password, use your GitHub username and PAT.
6. Enter your repository HTTPS URL.
7. In **Behaviors**, keep **Discover pull requests from origin** enabled.
   - Remove unwanted discovery behaviors if you only want origin PRs.
8. Under **Scan Multibranch Pipeline Triggers**, add **Periodically if not otherwise run** and set the interval to `5 minutes`.
9. Click **Save**.

Jenkins will scan the repository and create jobs for branches and PRs using the `Jenkinsfile` in this repo.

### 5.7. How the `Jenkinsfile` works

The `Jenkinsfile` in this repository:

- checks out the PR branch
- verifies that Ollama is reachable at `http://localhost:11434`
- compiles the `ai-reviewer` module
- runs the AI reviewer with:
  ```bash
  mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
  ```
- fails the pipeline when any review category returns `[FAILED]`
- reports a failed status to GitHub, which blocks merge until the issue is fixed

### 5.8. Run the AI reviewer locally first

Before connecting Jenkins, verify the reviewer works locally:

#### macOS / Linux
```bash
cd /Users/rahul/Playwright_Java_AG_AiReviewer
mvn clean compile
mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
```

#### Windows PowerShell
```powershell
Set-Location 'C:\Users\rahul\Playwright_Java_AG_AiReviewer'
mvn clean compile
mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
```

### 5.9. Running Playwright automation in Jenkins

If you want to use Jenkins for browser automation as well, keep it separate from the AI gate.

Use this exact command to run only the browser automation module:

```bash
mvn -pl playwright-tests test
```

To install Playwright browser engines in Jenkins, add a stage like:

```groovy
stage('Install Playwright Browsers') {
    steps {
        sh 'mvn exec:java -pl playwright-tests -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install --with-deps"'
    }
}
```

### 5.10. Windows-specific Jenkins and `ngrok` notes

If your Jenkins agent is Windows-based:

```powershell
Set-Location 'C:\path\to\repo'
mvn -pl playwright-tests test
```

For `ngrok` on Windows:

```powershell
ngrok.exe http 8080
```

### 5.11. Troubleshooting

- `ngrok` must be running and forwarding to the correct Jenkins port.
- Jenkins must be accessible in the browser at `http://localhost:8080` on the agent machine.
- If the build cannot see `GITHUB_TOKEN`, verify the credential and environment exposure in Jenkins.
- If the AI gate fails, inspect the console log and GitHub PR comments.
- If Playwright tests cannot launch browsers, install Playwright dependencies and run in headless mode.

### 5.12. Security notes

- Store GitHub tokens in Jenkins credentials only.
- Do not commit tokens into source control.
- Use the least privileges needed for PR review and status updates.
- Rotate tokens regularly.

### 5.13. New machine quick start

1. Install Java 17 or 21.
2. Install Maven 3.9+.
3. Install `ngrok`.
4. Install Jenkins (Docker recommended).
5. Start Jenkins and open `http://localhost:8080`.
6. Configure Jenkins credentials and the Multibranch Pipeline.
7. Set up the GitHub webhook using the `ngrok` forwarding URL.
8. Run the AI reviewer locally to verify before Jenkins.
9. Confirm the Jenkins job can access `http://localhost:11434` for Ollama.

This setup creates a local, secure Jenkins PR gate that blocks merges until AI review issues are addressed.


## 🌐 Local Webhook Tunnel Setup (GitHub to Local Jenkins)

Since Jenkins runs locally (`http://localhost:8080`), external GitHub servers cannot reach it directly. Use `ngrok` to expose a secure tunnel so GitHub can trigger your AI Code Quality Gate on every Pull Request event.

### 5.14. Install `ngrok`

#### macOS
```bash
brew install ngrok
```

#### Windows
```powershell
winget install ngrok
```

### 5.15. Configure `ngrok`

1. Sign up at https://ngrok.com and copy your auth token.
2. Run:
   ```bash
   ngrok config add-authtoken <YOUR_NGROK_AUTHTOKEN>
   ```
3. Confirm the token was saved.

### 5.16. Start the `ngrok` tunnel

Run this from your local machine where Jenkins is available:

```bash
ngrok http 8080
```

Leave the terminal open. Note the public forwarding URL shown by `ngrok`, for example:

```text
https://abcd-1234.ngrok-free.app
```

### 5.17. Configure GitHub webhook

In GitHub:

1. Go to your repository **Settings** -> **Webhooks** -> **Add webhook**.
2. Set **Payload URL** to:
   ```text
   https://<your-ngrok-url>/github-webhook/
   ```
3. Set **Content type** to `application/json`.
4. Leave **Secret** empty.
5. Choose **Let me select individual events**.
6. Check **Pull requests** only.
7. Click **Add webhook**.

Confirm GitHub shows a successful delivery and a green checkmark.

### 5.18. Verify local Jenkins and webhook delivery

- Open your Jenkins URL at `http://localhost:8080`.
- Confirm the Jenkins Multibranch Pipeline job is created and scanned.
- On GitHub, review the webhook delivery log for the `ngrok` URL.
- If the webhook delivery fails, restart `ngrok` and update the webhook URL.

### 5.19. Notes for new machines

- Install Java 17 or 21.
- Install Maven 3.9+.
- Install Jenkins and start it on port 8080.
- Install `ngrok` and configure it with your auth token.
- Start `ngrok` before creating the GitHub webhook.
- Run the AI reviewer locally first:
  ```bash
  cd /Users/rahul/Playwright_Java_AG_AiReviewer
  mvn clean compile
  mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
  ```
- Confirm Jenkins can reach Ollama at `http://localhost:11434` on the same machine.

This setup gives you a working local Jenkins PR gate for the AI review workflow.