# Playwright Java Automation with AI Helper

This repository contains:

- `playwright-tests`: Playwright + Cucumber UI automation framework
- `playwright-ai`: AI Planner/Generator/Healer helper module (Ollama + MCP)
- `playwright-ai.sh`: single entrypoint for AI operations

## Quick Start

```bash
# Run the full suite
mvn -pl playwright-tests test

# Plan / Generate / Heal
./playwright-ai.sh plan
./playwright-ai.sh generate
./playwright-ai.sh heal

# Auto-fix and verify failing tests
./playwright-ai.sh test-and-heal
```

## Documentation

All detailed documentation is centralized under `docs/`:

- Setup and installation: `docs/guides/installation.md`
- Daily operations: `docs/guides/daily-operations.md`
- AI helper usage: `docs/guides/ai-helper.md`
- Jenkins setup: `docs/jenkins_setup.md`
- Framework architecture: `docs/framework_architecture.md`

## CI Pipeline Scripts

All Jenkins pipeline scripts are grouped in:

- `docs/ci/jenkins/Jenkinsfile`
- `docs/ci/jenkins/Jenkinsfile.playwright-ai`
- `docs/ci/jenkins/Jenkinsfile.playwright-regression`

When creating Jenkins jobs, set the Pipeline Script Path to the relevant file above.
