#!/usr/bin/env zsh
set -euo pipefail

usage() {
    echo "Usage: $0 <command> [options]"
    echo ""
    echo "Commands:"
    echo "  plan          Create test execution plan"
    echo "  generate      Execute plan phases with Playwright MCP"
    echo "  heal          Diagnose latest failure and apply auto-fix"
    echo "  test-and-heal Run tests, auto-fix if possible, then re-run tests"
    exit 1
}

if [[ $# -lt 1 ]]; then
    usage
fi

COMMAND="$1"
shift

export PLAYWRIGHT_AI_HEALER_ACCESSIBILITY_CONTEXT_FILE="playwright-tests/target/healer-accessibility-context.txt"

case "$COMMAND" in
    plan)
        mvn -q -pl playwright-ai -DskipTests compile exec:java \
            -Dexec.mainClass=com.ai.playwrightaihelper.PlaywrightAIHelperCli -Dexec.args="planner"
        ;;
    generate)
        mvn -q -pl playwright-ai -DskipTests compile exec:java \
            -Dexec.mainClass=com.ai.playwrightaihelper.PlaywrightAIHelperCli -Dexec.args="generator"
        ;;
    heal)
        mvn -q -pl playwright-ai -DskipTests compile exec:java \
            -Dexec.mainClass=com.ai.playwrightaihelper.PlaywrightAIHelperCli -Dexec.args="healer"
        ;;
    test-and-heal)
        TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
        OUT_DIR="playwright-tests/target/ai-healer-reports"
        TEST_LOG="$OUT_DIR/test-run-$TIMESTAMP.log"
        HEALER_LOG="$OUT_DIR/healer-$TIMESTAMP.txt"
        RERUN_LOG="$OUT_DIR/test-rerun-$TIMESTAMP.log"

        mkdir -p "$OUT_DIR"

        echo "[PLAYWRIGHT-AI] Running Playwright tests..."
        set +e
        mvn -pl playwright-tests test | tee "$TEST_LOG"
        TEST_EXIT=${pipestatus[1]}
        set -e

        if [[ $TEST_EXIT -eq 0 ]]; then
            echo "[PLAYWRIGHT-AI] Tests passed. No healing needed."
            echo "[PLAYWRIGHT-AI] Test log: $TEST_LOG"
            exit 0
        fi

        echo "[PLAYWRIGHT-AI] Tests failed (exit $TEST_EXIT). Running healer auto-fix..."
        set +e
        "$0" heal | tee "$HEALER_LOG"
        HEALER_EXIT=${pipestatus[1]}
        set -e

        if [[ $HEALER_EXIT -ne 0 ]]; then
            echo "[PLAYWRIGHT-AI] Healer auto-fix failed (exit $HEALER_EXIT)."
            echo "[PLAYWRIGHT-AI] Test log: $TEST_LOG"
            echo "[PLAYWRIGHT-AI] Healer log: $HEALER_LOG"
            exit $HEALER_EXIT
        fi

        echo "[PLAYWRIGHT-AI] Re-running tests after auto-fix..."
        set +e
        mvn -pl playwright-tests test | tee "$RERUN_LOG"
        RERUN_EXIT=${pipestatus[1]}
        set -e

        if [[ $RERUN_EXIT -ne 0 ]]; then
            echo "[PLAYWRIGHT-AI] Re-run still failing (exit $RERUN_EXIT)."
            echo "[PLAYWRIGHT-AI] Initial test log: $TEST_LOG"
            echo "[PLAYWRIGHT-AI] Healer log: $HEALER_LOG"
            echo "[PLAYWRIGHT-AI] Re-run log: $RERUN_LOG"
            exit $RERUN_EXIT
        fi

        echo "[PLAYWRIGHT-AI] Auto-fix verified successfully."
        echo "[PLAYWRIGHT-AI] Initial test log: $TEST_LOG"
        echo "[PLAYWRIGHT-AI] Healer log: $HEALER_LOG"
        echo "[PLAYWRIGHT-AI] Re-run log: $RERUN_LOG"
        ;;
    *)
        usage
        ;;
esac
