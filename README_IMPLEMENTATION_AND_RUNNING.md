# Daily Operations Runbook

This document is only for day-to-day execution after setup is complete.
For one-time installation and Jenkins configuration, use:

- [README_INSTALLATION.md](README_INSTALLATION.md)
- [docs/jenkins_setup.md](docs/jenkins_setup.md)

---

## 1. Daily Startup Sequence

Run these in order at the start of your workday.

### 1.1 Open terminals at repository root

```bash
cd /Users/rahul/Playwright_Java_AG_AiReviewer
```

### 1.2 Start Ollama service

```bash
ollama serve
```

Optional check in another terminal:

```bash
curl -s -I http://localhost:11434
```

### 1.3 Start Jenkins

If using Homebrew on macOS:

```bash
brew services start jenkins-lts
```

If using Docker:

```bash
docker start jenkins
```

Jenkins URL:

```text
http://localhost:8080
```

### 1.4 Start ngrok tunnel

```bash
ngrok http 8080
```

Confirm webhook forwarding in ngrok dashboard:

```text
http://localhost:4040
```

---

## 2. Daily Local Commands

### 2.1 Run AI reviewer locally

Use this before pushing when you want a quick local gate check.

```bash
mvn clean compile
mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
```

### 2.2 Run Playwright tests locally

Default run:

```bash
mvn -pl playwright-tests clean test
```

Tagged run example:

```bash
mvn -pl playwright-tests clean test -Dcucumber.filter.tags="@SauceDemo" -Dworkers=2
```

Install browsers if needed:

```bash
mvn -pl playwright-tests exec:java -e -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install --with-deps"
```

---

## 3. Daily PR Workflow

### 3.1 Before creating/updating PR

```bash
git status
git add .
git commit -m "<your message>"
git push
```

### 3.2 What should trigger automatically

- AI reviewer PR pipeline runs for PR open/update.
- Regression pipeline is triggered from AI reviewer post-action.

### 3.3 What to check in Jenkins logs

- AI reviewer job should show reviewer output and PR comment posting summary.
- AI reviewer post-action should log regression trigger line for the job.
- Regression job should appear in build history as newly scheduled.

---

## 4. Daily Monitoring Checklist

### 4.1 Jenkins

- AI reviewer multibranch job status for current PR
- Regression job latest build status
- Console output for failures

### 4.2 GitHub PR

- AI inline comments posted on changed lines
- Jenkins checks reported on the PR

### 4.3 Artifacts

- Playwright videos, traces, screenshots in Jenkins artifacts
- Allure report link for regression job

---

## 5. End-of-Day Shutdown

### 5.1 Stop ngrok

Stop the terminal running ngrok with Ctrl+C.

### 5.2 Stop Jenkins

If Homebrew service:

```bash
brew services stop jenkins-lts
```

If Docker container:

```bash
docker stop jenkins
```

### 5.3 Stop Ollama model(s) and service

List active models:

```bash
ollama ps
```

Stop active model:

```bash
ollama stop qwen2.5-coder:14b
```

If running ollama serve in terminal, stop it with Ctrl+C.

---

## 6. Quick Troubleshooting (Daily)

### 6.1 AI reviewer says Ollama unreachable

- Ensure `ollama serve` is running.
- Verify endpoint: `http://localhost:11434`.

### 6.2 PR triggered AI reviewer but not regression

- Confirm PR commit includes latest `Jenkinsfile.ai-reviewer`.
- Confirm regression job name exists in Jenkins.
- Check AI reviewer post-action logs for downstream trigger warnings.

### 6.3 No AI comments on PR

- Check AI reviewer console output for GitHub API response codes.
- Ensure files/lines in findings are resolvable in PR diff.

---

## 7. Daily Commands Reference

```bash
# Start
ollama serve
brew services start jenkins-lts
ngrok http 8080

# Validate locally
mvn clean compile
mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"
mvn -pl playwright-tests clean test

# Stop
brew services stop jenkins-lts
ollama stop qwen2.5-coder:14b
```
