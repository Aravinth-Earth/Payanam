#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# render-changelogs.sh - render the newest CHANGELOG.md entry into the fastlane store changelogs.
#
# CHANGELOG.md is authored by hand; fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt is
# a derived copy read by F-Droid from the tagged commit. publish-release.ps1 refuses a stale copy.
#
# Usage: bash build-tools/scripts/render-changelogs.sh
set -uo pipefail
cd "$(dirname "$0")/../.."

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ── Declared version ─────────────────────────────────────────────────────────
read_property() {
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\(.*\)$/\1/p" gradle/version.properties |
        head -n1 | tr -d '[:space:]'
}

version="$(read_property version)"
version_code="$(read_property versionCode)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    fail "gradle/version.properties declares no major.minor.patch 'version' (found '${version}')."
[[ "$version_code" =~ ^[0-9]+$ ]] ||
    fail "gradle/version.properties declares no integer 'versionCode' (found '${version_code}')."

[ -f CHANGELOG.md ] || fail "CHANGELOG.md not found; it is the authored source for every release note."

# ── Entry selection ──────────────────────────────────────────────────────────
# Newest entry is the first '## [x.y.z]' heading; it must be the version being released.
newest_version="$(
    awk '
        /^##[[:space:]]/ && match($0, /[0-9]+\.[0-9]+\.[0-9]+/) {
            print substr($0, RSTART, RLENGTH)
            exit
        }
    ' CHANGELOG.md
)"
[ -n "$newest_version" ] || fail "CHANGELOG.md carries no '## [x.y.z]' entry."
if [ "$newest_version" != "$version" ]; then
    printf 'Add a "## [%s]" section above the %s entry first.\n' "$version" "$newest_version" >&2
    fail "CHANGELOG.md's newest entry is $newest_version, not the declared $version."
fi

# ── Body extraction ──────────────────────────────────────────────────────────
# Body = the lines of the '### <heading>' section inside the newest entry, with leading and trailing
# blank lines dropped and one trailing newline kept.
# The heading is matched as an ASCII prefix, so a mis-decoded non-ASCII section title cannot break it.
render_body() {
    awk -v heading="$1" -v want="$2" '
        function section_version(text) {
            if (match(text, /[0-9]+\.[0-9]+\.[0-9]+/)) { return substr(text, RSTART, RLENGTH) }
            return ""
        }
        /^##[[:space:]]/ {
            if (in_entry) { exit }
            if (section_version($0) == want) { in_entry = 1 }
            next
        }
        in_entry && /^###[[:space:]]/ {
            if (in_body) { exit }
            name = $0
            sub(/^###[[:space:]]+/, "", name)
            sub(/[[:space:]]+$/, "", name)
            in_body = (index(name, heading) == 1)
            next
        }
        in_body { lines[++count] = $0 }
        END {
            first = 0
            last = 0
            for (i = 1; i <= count; i++) {
                if (lines[i] ~ /^[[:space:]]*$/) { continue }
                if (first == 0) { first = i }
                last = i
            }
            for (i = first; i <= last && first > 0; i++) { print lines[i] }
        }
    ' CHANGELOG.md
}

locales=("en-US" "ta-IN")
# Section-heading prefix per locale, ASCII only: English, Tamil. The Tamil heading continues with the
# locale name in parentheses, so only its ASCII prefix is matched.
headings=("English" "Tamil")

# Every locale is rendered before any is written, so a missing section cannot leave a half-rendered tree.
staged_files=()
cleanup() {
    for staged in "${staged_files[@]:-}"; do
        [ -n "$staged" ] && rm -f "$staged"
    done
}
trap cleanup EXIT

for i in "${!locales[@]}"; do
    staged="$(mktemp)"
    staged_files+=("$staged")
    render_body "${headings[$i]}" "$version" > "$staged"
    if [ ! -s "$staged" ]; then
        fail "CHANGELOG.md entry $version has no '### ${headings[$i]}' body (locale ${locales[$i]})."
    fi
done

for i in "${!locales[@]}"; do
    target="fastlane/metadata/android/${locales[$i]}/changelogs/$version_code.txt"
    staged="${staged_files[$i]}"
    if [ -f "$target" ] && cmp -s "$staged" "$target"; then
        printf 'unchanged %s\n' "$target"
        continue
    fi
    # Replace atomically: render into a sibling temporary, prove it landed non-empty, then move it over
    # the target. A failed or empty write must leave the existing store file exactly as it was.
    target_dir="$(dirname "$target")"
    mkdir -p "$target_dir" || fail "could not create $target_dir; $target is untouched."
    incoming="$(mktemp "$target_dir/.changelogs.XXXXXX")" ||
        fail "could not create a temporary file in $target_dir; $target is untouched."
    staged_files+=("$incoming")
    cp "$staged" "$incoming" || fail "could not write $target; the existing file is untouched."
    [ -s "$incoming" ] || fail "rendered body for ${locales[$i]} is empty; $target is untouched."
    # mktemp creates 0600; keep whatever mode the store file already had.
    chmod --reference="$target" "$incoming" 2>/dev/null || chmod 0644 "$incoming" 2>/dev/null || true
    mv -f "$incoming" "$target" || fail "could not replace $target; the existing file is untouched."
    printf 'wrote     %s\n' "$target"
done
