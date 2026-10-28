#!/usr/bin/env bash
# Cut the next working branch from dev.
#
# The lifecycle this script exists to make habitual:
#
#   scripts/new-branch.sh feat foundation        ->  feat/foundation
#   ...work, commit, push...
#   gh pr create --base dev --head feat/foundation --title "feat(build): ..."
#   ...CI green, PR merged, GitHub deletes the branch...
#   scripts/new-branch.sh fix node-distribution
#
# Two rules, and they are different rules:
#
#   * The BRANCH carries only the conventional-commit *type*. No version, no semver: a
#     branch is a place to do one change, not a release record.
#   * The PR TITLE is a full conventional commit, type and optional scope, and it is the
#     title that is checked. The title does not have to match the branch name.
#
# Always branched from dev, never from main: dev is the integration line, and a branch cut
# from main cannot see the work dev has already accepted.
set -euo pipefail

type="${1:-}"
slug="${2:-}"

if [[ -z "$type" || -z "$slug" ]]; then
    echo "usage: $0 <type> <slug>" >&2
    echo "  type:  feat | fix | chore | refactor | perf | docs | test | build | ci | style | revert" >&2
    echo "  slug:  short lowercase description, dashes" >&2
    echo >&2
    echo "example: $0 feat foundation   ->  feat/foundation" >&2
    exit 2
fi

if [[ ! "$type" =~ ^(feat|fix|chore|refactor|perf|docs|test|build|ci|style|revert)$ ]]; then
    echo "error: '$type' is not a conventional-commit type" >&2
    echo "       one of: feat fix chore refactor perf docs test build ci style revert" >&2
    exit 1
fi

branch="${type}/${slug}"

# Same rule the branch-policy workflow enforces, checked here so a mistake is caught before a
# push rather than by a red check after one.
pattern='^(feat|fix|chore|refactor|perf|docs|test|build|ci|style|revert)/[a-z0-9][a-z0-9._-]*$'
if [[ ! "$branch" =~ $pattern ]]; then
    echo "error: '$branch' is not a valid branch name" >&2
    echo "       expected <type>/<slug>, slug lowercase with dashes" >&2
    exit 1
fi

# A slug may not look like a version. A branch is one change, not a release record, so
# `feat/v0.2.0-canaries` is rejected even though the type is right.
if [[ "$slug" =~ ^v[0-9] ]]; then
    echo "error: '$branch' looks like a release branch" >&2
    echo "       a branch is one change; the version lives in the PR title" >&2
    echo "       valid:   feat/foundation      invalid:  feat/v0.1.0-foundation" >&2
    exit 1
fi

if git rev-parse --verify "$branch" >/dev/null 2>&1; then
    echo "error: '$branch' already exists locally" >&2
    exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
    echo "error: the working tree is dirty. Commit or stash before cutting a branch." >&2
    exit 1
fi

git fetch --quiet origin dev
git checkout --quiet -b "$branch" origin/dev

echo "created $branch from origin/dev"
echo
echo "next:  git push -u origin $branch"
echo "then:  scripts/pr-title.sh \"<type>(<scope>): <description>\"   # checks it before CI does"
echo "then:  gh pr create --base dev --head $branch --title \"<type>(<scope>): <description>\""
