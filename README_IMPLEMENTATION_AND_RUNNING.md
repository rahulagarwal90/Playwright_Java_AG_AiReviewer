# Framework Execution Guide (`README_IMPLEMENTATION_AND_RUNNING.md`)

This guide outlines execution guidelines for both local developers and Jenkins pipeline orchestration. It is structured into two main contexts: **Local Runs** and **Pipeline Runs**.

---

## 💻 1. Local Runs (Manual Execution)

Both modules must be run from the **project root directory** to ensure Maven parses the multi-module reactor system correctly.

---

### A. Playwright Browser Automation (`playwright-tests`)

This module executes automated web suites using Playwright BDD Cucumber.

#### 🍎 macOS (Terminal)
* **Standard execution** (headless, default `@SauceDemo` tags, 2 workers):
  ```bash
  mvn -pl playwright-tests clean test
  ```
* **Parametrized execution** (run Chrome headlessly, `@SauceDemo` tags, 4 parallel workers):
  ```bash
  mvn -pl playwright-tests clean test -Dbrowser.headless=true -Dbrowser=chrome -Dcucumber.filter.tags="@SauceDemo" -Dworkers=4
  ```
* **Install Playwright binaries manually**:
  ```bash
  mvn -pl playwright-tests exec:java -e -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install --with-deps"
  ```

#### 🪟 Windows (PowerShell)
* **Standard execution** (headless, default `@SauceDemo` tags, 2 workers):
  ```powershell
  mvn -pl playwright-tests clean test
  ```
* **Parametrized execution** (run Chrome headlessly, `@SauceDemo` tags, 4 parallel workers):
  ```powershell
  mvn -pl playwright-tests clean test "-Dbrowser.headless=true" "-Dbrowser=chrome" "-Dcucumber.filter.tags=@SauceDemo" "-Dworkers=4"
  ```
* **Install Playwright binaries manually**:
  ```powershell
  mvn -pl playwright-tests exec:java -e -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install --with-deps"
  ```

---

### B. AI Reviewer (`ai-reviewer`)

The AI reviewer extracts local unstaged and staged changes using `git diff HEAD`, annotates them with line numbers, and sends them to your local Ollama instance for validation.

*Note: You must have active staged/unstaged changes in the repository for the diff generator to catch. Otherwise, it logs `No git changes detected.`*

#### 🍎 macOS (Terminal)
* **Run review engine**:
  ```bash
  # Step 1: Compile the project
  mvn clean compile
  
  # Step 2: Trigger the reviewer main class
  mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
  ```

#### 🪟 Windows (PowerShell)
* **Run review engine**:
  ```powershell
  # Step 1: Compile the project
  mvn clean compile
  
  # Step 2: Trigger the reviewer main class
  mvn -pl ai-reviewer exec:java "-Dexec.mainClass=com.ai.reviewer.LocalCodeReviewer"
  ```

---

## 🚀 2. Pipeline Runs (Jenkins Execution)

To isolate testing pipelines and AI analysis, two separate pipelines are created in Jenkins.

### Pipeline A: The Pull Request AI Reviewer Gate (`Jenkinsfile.ai-reviewer`)
- **Trigger**: Automatically triggered by GitHub webhook on PR actions (open, update/sync).
- **Behavior**: Checks out the PR code, checks if local Ollama (`http://localhost:11434`) is running on the host, injects the `github-pr-token` to authenticate with GitHub, runs the reviewer code, and fails the build if flaws are found to block merging. It runs **no browser tests**.

### Pipeline B: Scheduled/On-Demand Playwright Regression (`Jenkinsfile.playwright-regression`)
- **Trigger**: Runs automatically every night at 2:00 AM (`cron('H 02 * * *')`) or manually via the Jenkins UI with parameter selection.
- **Behavior**: Installs Playwright browser dependencies inside the workspace, executes headless tests, parses Surefire/JUnit reports, and archives media artifacts (traces, screenshots, videos).

---

### ⚙️ How to Configure Pipelines in Jenkins UI

Follow these steps to create the dual pipeline jobs:

#### 1. Configure the AI Reviewer PR Gate Job
1. On the Jenkins Dashboard, click **New Item**.
2. Name the job: `playwright-framework-ai-reviewer`.
3. Select **Multibranch Pipeline** and click **OK**.
4. Under **Branch Sources**, click **Add source** -> **GitHub**.
5. Select your GitHub credentials, and enter the Repository HTTPS URL:
   `https://github.com/<your-username>/Playwright_Java_AG_AiReviewer.git`
6. Under **Build Configuration**, change **Script Path** from `Jenkinsfile` to:
   `Jenkinsfile.ai-reviewer`
7. Under **Scan Multibranch Pipeline Triggers**, check **Periodically if not otherwise run** and set the interval to `5 minutes` or `1 minute`.
8. Click **Save**. Jenkins will scan the branches and automatically start a job if a PR branch is discovered containing `Jenkinsfile.ai-reviewer`.

#### 2. Configure the Playwright Regression Job
1. On the Jenkins Dashboard, click **New Item**.
2. Name the job: `playwright-regression-suite`.
3. Select **Pipeline** (a standard Pipeline job is recommended here for parameterized on-demand runs) and click **OK**.
4. Scroll to **Build Triggers** and check **Build periodically**. Set the schedule to:
   ```text
   H 02 * * *
   ```
5. Check **This project is parameterized** and configure three parameters:
   - **Choice Parameter**:
     * Name: `BROWSER`
     * Choices: `chrome`, `firefox`, `webkit`
     * Description: `Select browser for execution`
   - **String Parameter**:
     * Name: `TAGS`
     * Default Value: *(Leave blank)*
     * Description: `Enter Cucumber filter tags (e.g., @SauceDemo). Leave empty to execute ALL tests.`
   - **String Parameter**:
     * Name: `WORKERS`
     * Default Value: `2`
     * Description: `Parallel execution thread count`
6. Scroll down to **Pipeline** -> **Definition**: Select **Pipeline script from SCM**.
7. Select **Git** and enter your repository URL:
   `https://github.com/<your-username>/Playwright_Java_AG_AiReviewer.git`
8. In **Branch Specifier**, enter `*/main` (or your default branch).
9. Change the **Script Path** to:
   `Jenkinsfile.playwright-regression`
10. Click **Save**.

---

### 🔍 How to Monitor Webhooks & View Build Logs

#### Webhook Handshake Verification via ngrok
1. While `ngrok` is running, open a browser and navigate to its local administrative interface:
   `http://localhost:4040`
2. This local dashboard shows all incoming HTTP POST requests forwarded from GitHub to your local Jenkins server (`http://localhost:8080/github-webhook/`).
3. Click on any `POST /github-webhook/` event to view headers, JSON payloads, and response codes (`200 OK` indicates successful delivery to Jenkins).
4. If deliveries fail, verify your ngrok forwarding endpoint matches your GitHub Webhook configuration exactly.

#### Monitoring Jenkins Build Logs
1. Open Jenkins in your browser (`http://localhost:8080`).
2. Navigate to your job:
   - For PR builds: Click on `playwright-framework-ai-reviewer` -> select the active Pull Request branch -> select the latest build number.
   - For regression: Click on `playwright-regression-suite` -> select the latest build number.
3. Click on **Console Output** in the left-hand menu.
4. Review stdout logs:
   - In **AI Reviewer** logs, check the section starting with `==================================================` to view model comments and find out if it succeeded or blocked.
   - In **Playwright Regression** logs, inspect Cucumber test run output and confirm surefire report generation.
5. For regression runs, click on **Build Artifacts** at the top of the build screen to download archived videos, traces, and screenshots.
