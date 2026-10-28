#!/usr/bin/env bash
# Check a PR title against the rule branch-policy.yml enforces, before creating the PR.
#
#   scripts/pr-title.sh "fix(codegen): elvis binds tighter than ==, so the rung moves"
#
# Why it exists: `scripts/new-branch.sh` validates the *branch* against the same workflow and
# prints the `gh pr create` line, but the title was unchecked. The two halves of one workflow
# had one pre-flight and one red check, and the missing half costs a full CI cycle to find out
# — a title starting with `?:`, or an uppercase type name, is refused for a character.
#
# The pattern is **read out of `.github/workflows/branch-policy.yml`, not copied here.** A second
# copy of a policy is a second thing to update, and a stale one is a rule that lies: this project
# has already shipped a gradle flag documented as enforced when nothing passed it. If the
# extraction finds nothing, this says so rather than falling back to a remembered copy.
#
# What it cannot do is predict the workflow's other checks, and it does not try: run it, and if
# it passes, the title is the only thing it was ever able to tell you about.
set -euo pipefail

WORKFLOW=".github/workflows/branch-policy.yml"
title="${1:-}"

if [[ -z "$title" ]]; then
    echo "usage: $0 \"<type>(<scope>): <description>\"" >&2
    echo >&2
    echo "  types:  feat fix chore refactor perf docs test build ci style revert" >&2
    echo >&2
    echo "  The description must start lowercase or a digit, must be under 100 characters," >&2
    echo "  and must not end with a period." >&2
    exit 2
fi

if [[ ! -f "$WORKFLOW" ]]; then
    echo "error: $WORKFLOW not found; run this from the repository root" >&2
    exit 1
fi

# The title pattern is the second `pattern=` in that file: the first is the branch rule, which
# `scripts/new-branch.sh` owns. Taking the wrong one would validate the wrong thing silently.
pattern="$(sed -n "s/^ *pattern='\(.*\)'$/\1/p" "$WORKFLOW" | sed -n '2p')"

if [[ -z "$pattern" ]]; then
    echo "error: no title pattern found in $WORKFLOW" >&2
    echo "       this script reads the rule rather than copying it, so it has nothing to check" >&2
    exit 1
fi

if [[ ! "$title" =~ $pattern ]]; then
    echo "error: '$title' does not match branch-policy.yml's title rule" >&2
    echo >&2
    echo "Format:  <type>[(<scope>)][!]: <description>" >&2
    echo "Types:   feat fix chore refactor perf docs test build ci style revert" >&2
    echo >&2
    echo "The description starts after ': ' and must begin lowercase or a digit — a type name" >&2
    echo "there fails on the capital, and an operator or symbol there fails on the character." >&2
    exit 1
fi

# The workflow's other two checks, so this script and it agree about what a title is.
if (( ${#title} > 100 )); then
    echo "error: '$title' is ${#title} characters; the limit is 100" >&2
    exit 1
fi

if [[ "$title" == *"." ]]; then
    echo "error: '$title' ends with a period; a conventional-commit description is a phrase" >&2
    exit 1
fi

echo "ok: '$title' satisfies branch-policy.yml"