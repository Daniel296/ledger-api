#!/usr/bin/env bash
# Stop hook: a step marked as implemented must get its independent review before the turn may end.
marker="$CLAUDE_PROJECT_DIR/.claude/review-pending"
[ -f "$marker" ] || exit 0

# Already sent back once by this hook: let the turn end instead of looping
if jq -e '.stop_hook_active == true' > /dev/null 2>&1; then
    exit 0
fi

step=$(head -c 200 "$marker")
jq -n --arg step "$step" '{
    decision: "block",
    reason: ("\($step) is implemented but has not been reviewed: launch the ledger-reviewer agent"
        + " on the uncommitted diff, then delete .claude/review-pending.")
}'
