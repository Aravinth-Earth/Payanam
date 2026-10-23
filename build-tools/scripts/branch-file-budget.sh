#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# branch-file-budget.sh - PRE-CHANGE gate: does this branch still have room for the work ahead?
#
# Counts every path this branch changes against its base branch - committed (vs the merge-base),
# staged, unstaged and untracked-non-ignored - because all of it becomes PR review surface.
# Run it BEFORE adding, editing or deleting anything, and again before starting each new chunk.
# A branch at or over the ceiling is not reviewable: finish the chunk, propose the PR, branch fresh.
#
# Usage: bash build-tools/scripts/branch-file-budget.sh [--base <branch>] [--limit <n>] [--need <n>] [--override "<reason>"]
# Exit: 0 healthy, 3 exhausted or too small to extend, 2 usage or repo error.

set -euo pipefail

LIMIT=80
NEED=0
BASE=""
OVERRIDE=""

usage() { sed -n '5,13p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "branch-file-budget: $1" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --base|--limit|--need|--override)
            [[ $# -ge 2 ]] || die "$1 needs a value"
            case "$1" in
                --base)     BASE="$2" ;;
                --limit)    LIMIT="$2" ;;
                --need)     NEED="$2" ;;
                --override) OVERRIDE="$2" ;;
            esac
            shift 2 ;;
        *) die "unknown argument: $1 (try --help)" ;;
    esac
done

case "$LIMIT" in ''|*[!0-9]*) die "--limit must be a positive integer" ;; esac
case "$NEED" in ''|*[!0-9]*) die "--need must be a non-negative integer" ;; esac
[[ "$LIMIT" -gt 0 ]] || die "--limit must be greater than zero"
[[ -z "$OVERRIDE" || -n "${OVERRIDE//[[:space:]]/}" ]] || die "--override needs a reason"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not a git repository"

if [[ -z "$BASE" ]]; then
    for candidate in dev origin/dev main origin/main; do
        if git rev-parse --verify --quiet "$candidate^{commit}" >/dev/null; then BASE="$candidate"; break; fi
    done
fi
[[ -n "$BASE" ]] || die "no base branch found (tried dev, origin/dev, main, origin/main); pass --base"
git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null || die "base branch not found: $BASE"

MERGE_BASE="$(git merge-base HEAD "$BASE" 2>/dev/null || true)"
[[ -n "$MERGE_BASE" ]] || die "no merge-base between HEAD and $BASE"

files="$({
    git diff --name-only --diff-filter=ACDMRTUXB "$MERGE_BASE" HEAD
    git diff --name-only --diff-filter=ACDMRTUXB "$MERGE_BASE"
    git ls-files --others --exclude-standard
} | sed '/^[[:space:]]*$/d' | sort -u)"

COUNT=0
[[ -z "$files" ]] || COUNT="$(printf '%s\n' "$files" | wc -l | tr -d '[:space:]')"
HEAD_BRANCH="$(git symbolic-ref -q --short HEAD || echo 'detached HEAD')"
HEADROOM=$((LIMIT - COUNT))

echo "base branch:            $BASE"
echo "merge-base:             $MERGE_BASE"
echo "head branch:            $HEAD_BRANCH"
echo "current changed files:  $COUNT"
echo "limit:                  $LIMIT"
echo "headroom:               $HEADROOM"
echo "planned files:          $NEED"

if [[ "$COUNT" -ge 60 && "$COUNT" -lt "$LIMIT" ]]; then
    echo "ADVISORY: branch is near the ceiling ($COUNT/$LIMIT) - finish the current chunk and propose it as a PR before starting new scope."
fi

if [[ "$COUNT" -ge "$LIMIT" ]]; then
    verdict="EXHAUSTED"; code=3
elif [[ "$HEADROOM" -lt "$NEED" ]]; then
    verdict="TOO SMALL"; code=3
else
    verdict="HEALTHY ($COUNT/$LIMIT, $HEADROOM left)"; code=0
fi

if [[ "$code" -ne 0 && -z "$OVERRIDE" ]]; then
    echo "BRANCH BUDGET: $verdict"
    exit 3
elif [[ "$code" -ne 0 ]]; then
    echo "WARNING: BRANCH BUDGET OVERRIDE ($verdict) - Aravinth approved an oversized branch this session: $OVERRIDE"
fi

echo "BRANCH BUDGET: $verdict"
