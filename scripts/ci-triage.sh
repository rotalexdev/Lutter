#!/usr/bin/env bash
# Read a CI failure without reading the whole log.
#
#   scripts/ci-triage.sh checks [pr]      -> what is still pending, and what needs a human
#   scripts/ci-triage.sh triage [run-id]  -> the failure as file:line plus message, deduplicated
#
# The lifecycle this script exists to make habitual:
#
#   git push
#   scripts/ci-triage.sh checks 21        -> "check: running", "conformance: needs approval"
#   scripts/ci-triage.sh checks 21        -> "all green on 9066112"
#   ...or, when check is red...
#   scripts/ci-triage.sh triage           -> the defects, one per line, errors only
#   ...fix, commit, push, repeat...
#
# Why it exists, in the shape it takes: a GitHub Actions log for this project runs to several
# thousand lines, and the compiler's findings are column-formatted four fields deep. Reading one
# by hand means a chain of grep/sed/awk per investigation, and that chain is where the mistakes
# happen. This repository has misdiagnosed a red build four separate times, and the two that
# were caught cost a commit each. `triage` exists to make the count and the split between
# errors and warnings a single command, because `-Werror` is off in this build and only a
# compiler *error* is a red.
#
# `checks` exists because a conclusion of `action_required` means GitHub is waiting for a human
# to approve the run, and it looks exactly like a failure in a quick scan. It is not one, and
# reading it as one sends you looking for a bug in your own workflow.
#
# Requires: gh (authenticated), python3. Deliberately no jq — it is not installed here, and
# shelling out to a missing binary to parse one field would be worse than using what exists.
set -euo pipefail

usage() {
    echo "usage: $0 checks [pr-number]" >&2
    echo "       $0 triage [run-id]" >&2
    echo >&2
    echo "  checks  summarise the checks on a PR, naming what needs a human" >&2
    echo "  triage  reduce a failed run's log to file:line plus message" >&2
    echo >&2
    echo "  triage with no run-id uses the current branch's latest failed run." >&2
    exit 2
}

# `gh` exits non-zero when a run failed, which is the normal case here and not an error.
run_gh() { gh "$@" || true; }

current_branch() {
    git rev-parse --abbrev-ref HEAD 2>/dev/null || echo ""
}

latest_failed_run() {
    run_gh run list --branch "$(current_branch)" --limit 20 \
        --json databaseId,conclusion \
        --jq '[.[] | select(.conclusion == "failure")][0].databaseId // empty'
}

cmd_checks() {
    local pr="${1:-}"
    [[ -z "$pr" ]] && { echo "error: give a PR number" >&2; usage; }

    local json
    json="$(run_gh pr view "$pr" --json headRefOid,state,isDraft,mergeable 2>/dev/null || true)"
    if [[ -z "$json" ]]; then
        echo "error: cannot read PR #$pr (no gh auth, or no such PR)" >&2
        exit 1
    fi

    local full_head head state
    full_head="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("headRefOid",""))')"
    head="${full_head:0:7}"
    state="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))')"
    echo "PR #$pr  head ${head:-?}  $state"
    echo

    local rows
    rows="$(run_gh pr checks "$pr" 2>/dev/null || true)"
    if [[ -z "$rows" ]]; then
        echo "no checks reported on this branch."
        echo
        echo "Two different things produce an empty list, and they need opposite reactions:"
        echo "  * a required run still awaiting approval  -> approve it on the PR's Checks tab"
        echo "  * the head commit has no PR-triggered run -> push again, or re-run the workflows"
        echo
        echo "Read the head commit's own runs to tell them apart:"
        run_gh run list --commit "$full_head" \
            --limit 5 --json workflowName,event,status,conclusion \
            --jq '.[] | "  \(.workflowName)  \(.event)  \(.status)  \(.conclusion // "-")"'
        return 0
    fi

    printf '%s\n' "$rows"

    echo
    if printf '%s\n' "$rows" | grep -q 'action_required'; then
        echo "action_required above means GitHub is waiting for a human to approve the run."
        echo "It is not a failure. Approve on the PR's Checks tab and re-run this script."
        return 0
    fi
    if printf '%s\n' "$rows" | grep -qE '\bfail\b'; then
        echo "something failed.  scripts/ci-triage.sh triage  ->  the defects, errors only"
        return 1
    fi
    if printf '%s\n' "$rows" | grep -qE 'pending|in_progress|queued'; then
        echo "still running.  re-run this script in a couple of minutes."
        return 0
    fi
    echo "all green."
}

cmd_triage() {
    local run="${1:-}"
    if [[ -z "$run" ]]; then
        run="$(latest_failed_run)"
        if [[ -z "$run" ]]; then
            echo "no failed run found on $(current_branch)" >&2
            exit 1
        fi
        echo "# no run-id given; using $run (latest failure on $(current_branch))"
    fi

    # Not `local`: the EXIT trap fires after this function has returned, and a local would
    # already be out of scope by then.
    TRIAGE_LOG="$(mktemp)"
    trap 'rm -f "${TRIAGE_LOG:-}"' EXIT
    run_gh run view "$run" --log-failed >"$TRIAGE_LOG" 2>/dev/null || true

    if [[ ! -s "$TRIAGE_LOG" ]]; then
        echo "run $run produced no failed-step log (it may not have finished, or it passed)" >&2
        exit 1
    fi

    echo "# run $run"
    run_gh run view "$run" --json headSha,conclusion \
        --jq '"# head \(.headSha[0:7])  conclusion \(.conclusion)"' || true
    echo

    # The Actions problem report puts the message two indented lines above its Location, so the
    # parse walks backwards from the Location rather than forwards from the message. The Kotlin
    # CLI's own `e: file:///...:LINE:COL` lines are handled too: a project can emit either
    # depending on which plugin reported the problem.
    python3 - "$TRIAGE_LOG" <<'PYTHON'
import re, sys
from collections import OrderedDict

repo_markers = ("/home/runner/work/", "/Users/runner/work/", "/workspace/")

def strip_repo(path):
    """Cut a runner absolute path down to a repository-relative one.

    GitHub lays these out as <root>/<owner>/<repo>/<repo>/<path...>, so two segments come off
    after the marker, not one — dropping only the owner leaves `Lutter/engine/...` and every
    path in the report is then wrong in the same way, which is worse than no trimming at all.
    """
    for marker in repo_markers:
        index = path.find(marker)
        if index != -1:
            parts = path[index + len(marker):].split("/")
            return "/".join(parts[2:]) if len(parts) > 2 else path
    return path

errors = OrderedDict()
warnings = OrderedDict()

lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()

timestamp = re.compile(r"^\d{4}-\d{2}-\d{2}T[\d:.]+Z\s*")

def payload(line):
    """The message column of a log line, without the job/step/time columns.

    Actions prefixes each line as `<job>\t<step>\t<ISO-8601 Z>\t<message>`, so the last tab
    field still carries the timestamp and it has to come off before the message is readable.
    """
    parts = line.split("\t")
    return timestamp.sub("", parts[-1] if len(parts) > 1 else line)

for index, raw in enumerate(lines):
    line = payload(raw)

    location = re.search(r"Location:\s+(\S+)\s+line\s+(\d+)", line)
    if location:
        path, number = strip_repo(location.group(1)), location.group(2)
        message = ""
        for back in range(1, 5):
            if index - back < 0:
                break
            candidate = payload(lines[index - back]).strip()
            if not candidate or candidate.startswith("Problem found"):
                continue
            if candidate.startswith("Location:") or re.match(r"^> Task ", candidate):
                break
            message = candidate
            break
        errors.setdefault(f"{path}:{number}", message or "(no message above the Location)")
        continue

    kotlin = re.match(r"^([ew]): file://(\S+?):(\d+):(\d+)\s+(.*)$", line.strip())
    if kotlin:
        kind, path, number, _, message = kotlin.groups()
        target = warnings if kind == "w" else errors
        target.setdefault(f"{strip_repo(path)}:{number}", message.strip())

def dump(title, table, note=None):
    if not table:
        return
    print(f"## {title} ({len(table)})")
    if note:
        print(note)
    for where, message in table.items():
        print(f"  {where}  {message}")
    print()

dump("errors", errors,
     "Only these are a red. `-Werror` is off, so a warning cannot fail `check`.")
dump("warnings", warnings,
     "Not a red. Listed so a warning is never mistaken for the cause of one.")

if not errors:
    print("no compiler errors in the failed log.")
    print("Read the summary at the end of the run: a red with no compiler error is usually")
    print("a failed task (ABI, module graph, a test assertion) rather than a compile problem.")
PYTHON
}

case "${1:-}" in
    checks) shift; cmd_checks "${1:-}" ;;
    triage) shift; cmd_triage "${1:-}" ;;
    *) usage ;;
esac
