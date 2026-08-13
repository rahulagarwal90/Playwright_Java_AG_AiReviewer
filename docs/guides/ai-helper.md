# Playwright AI Helper Guide

This guide outlines how to use the Playwright AI Agent framework to plan test flows, execute them using browser tools, and automatically repair test failures.

---

## 📋 Table of Contents
1. [Core Concepts: MCP vs. CLI](#1-core-concepts-mcp-vs-cli)
2. [Prerequisites & Setup](#2-prerequisites--setup)
3. [Unified CLI Tool Usage](#3-unified-cli-tool-usage)
4. [Step-by-Step Execution Guide](#4-step-by-step-execution-guide)
   - [Step 4.1: Planner (CLI Mode)](#step-41-planner-cli-mode)
   - [Step 4.2: Generator (MCP Mode)](#step-42-generator-mcp-mode)
   - [Step 4.3: Healer (CLI & Failure Snapshot Mode)](#step-43-healer-cli--failure-snapshot-mode)

---

## 1. Core Concepts: MCP vs. CLI

Our framework leverages a clean, industry-standard separation of concerns between browser-level actions and cognitive automation logic:

*   **Playwright MCP (`@playwright/mcp`):** This is the **Model Context Protocol** browser control layer. It runs as a background server to provide a standardized toolset (navigation, clicking, typing, matchesAriaSnapshot) that allows AI agents to directly view and interact with the page.
*   **The CLI Entrypoint (`playwright-ai.sh`):** This is the **orchestrator** wrapper which executes the three main agent roles using your local Ollama + Qwen engine:
    1.  **Planner:** Reasoner that breaks high-level test goals down into steps (does not require active browser control).
    2.  **Generator:** Interactive runner that uses **Playwright MCP** to execute the generated plan phases directly inside the browser.
    3.  **Healer:** Diagnostics analyzer that takes surefire test logs and the failing page's ARIA snapshot (captured via Playwright BDD Hooks) to generate fixes.

---

## 2. Prerequisites & Setup

1.  **Start Ollama:**
    Ensure Ollama is running locally:
    ```bash
    ollama serve
    ```
2.  **Verify Model Availability:**
    Ensure `qwen2.5-coder:14b` is available (or use `qwen2.5-coder:7b` for lower-RAM setups):
    ```bash
    ollama pull qwen2.5-coder:14b
    ```
3.  **Build Helper Project:**
    Compile the `playwright-ai` module:
    ```bash
    mvn -pl playwright-ai -DskipTests clean compile
    ```

---

## 3. Unified CLI Tool Usage

The framework consolidates all execution modes into a single, minimalist CLI script located at the repository root: **`./playwright-ai.sh`**.

```bash
Usage: ./playwright-ai.sh <command>

Commands:
  plan          Decompose a high-level UI automation goal into step-by-step phases
  generate      Execute BDD/browser phase steps using Playwright MCP tools
   heal          Diagnose latest failure and apply auto-fix
   test-and-heal Run tests, auto-fix failures if possible, then re-run tests
```

---

## 4. Step-by-Step Execution Guide

### Step 4.1: Planner (CLI Mode)
Decomposes a human goal into actionable UI testing milestones.

1. Run the Planner:
   ```bash
   ./playwright-ai.sh plan
   ```
2. Input your test goal:
   ```text
   Enter goal (or type EXIT):
   Open https://www.saucedemo.com, login as standard_user, add Sauce Labs Fleece Jacket to cart, complete checkout, and verify order completion message.
   ```
3. The Planner will output a structured plan split by `---` containing objectives and expected observable milestones.

---

### Step 4.2: Generator (MCP Mode)
Uses the Playwright MCP server to interactively launch and drive a chromium browser to execute plan steps.

1. Run the Generator:
   ```bash
   ./playwright-ai.sh generate
   ```
2. Input the BDD goal.
3. Select an execution path:
   - Enter a specific phase number (e.g. `1`) to execute only that step.
   - Enter `ALL` to run the entire phase sequence.
   - Enter `NEWGOAL` to switch the active goal, or `EXIT` to leave.
4. The agent will spin up the headless/headful browser, execute the tools via MCP stdio, and report completion status (`SUCCESS` / `FAILURE`).

---

### Step 4.3: Healer (CLI & Failure Snapshot Mode)
Repairs broken test locators or timing issues using surefire failure reports and page ARIA accessibility trees.

#### Automatic Self-Healing Flow (Recommended)
This workflow executes tests, captures diagnostics, applies safe locator fixes automatically, and verifies with a rerun:

1. Run the integrated test-and-heal pipeline:
   ```bash
   ./playwright-ai.sh test-and-heal
   ```
2. **Behind the scenes:**
   - Playwright executes the Cucumber regression suite.
   - If a test fails (e.g. due to an incorrect locator like `#userEmai`), Cucumber's BDD teardown hook (`Hooks.java`) automatically dumps the page's accessibility tree as an ARIA snapshot to `playwright-tests/target/healer-accessibility-context.txt`.
   - The CLI runs Healer, passes surefire stack trace + ARIA snapshot, applies safe source fixes, and re-runs tests.
3. The Healer outputs diagnosis and fix details:
   - **Diagnosis:** Identifies the broken locator.
   - **Corrective Action:** Proposes the corrected element selector (e.g. `#userEmail`) and applies it when safe.
   - **Retryable:** Indicates if a rerun could resolve the failure.

#### Explicit Healer Command
To run healer directly without a full test cycle:
```bash
./playwright-ai.sh heal
```
