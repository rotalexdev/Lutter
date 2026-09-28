#!/usr/bin/env bash
# Cut the next working branch from dev, with a semantic version in its name.
#
# The lifecycle this script exists to make habitual:
#
#   scripts/new-branch.sh feat 0.1.0 foundation   ->  feat/v0.1.0-foundation
#   ...work, commit, push...
#   gh pr create --base dev --head feat/v0.1.0-foundation
#   ...CI green, PR merged, GitHub deletes the branch...
#   scripts/new-branch.sh feat 0.1.1 canary-scope
#
# Always branched from dev, never from main: dev is the integration line, and a branch cut
# from main cannot see the work dev has already accepted.
set -euo pipefail

type="${1:-}"
version="${2:-}"
slug="${3:-}"

if [[ -z "$type" || -z "$version" ]]; then
    echo "usage: $0 <type> <version> <slug>" >&2
    echo "  type:    feat | fix | chore | refactor | perf | docs | test | build | ci" >&2
    echo "  version: MAJOR.MINOR.PATCH, e.g. 0.1.0   (or 0.2.0-canaries for a prerelease)" >&2
    echo "  slug:    short description, lowercase, dashes" >&2
    echo >&2
    echo "example: $0 feat 0.1.0 foundation   ->  feat/v0.1.0-foundation" >&2
    exit 2
fi

branch="${type}/v${version}"
if [[ -n "$slug" ]]; then
    branch="${branch}-${slug}"
fi

# Same rule the branch-policy workflow enforces, checked here so a mistake is caught before
# a push rather than by a red check after one.
pattern='^(feat|fix|chore|refactor|perf|docs|test|build|ci)/v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9a-z.-]+)?$'
if [[ ! "$branch" =~ $pattern ]]; then
    echo "error: '$branch' is not a valid branch name" >&2
    echo "       expected <type>/v<MAJOR>.<MINOR>.<PATCH>[-<prerelease>]" >&2
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
echo "then:  gh pr create --base dev --head $branch"
