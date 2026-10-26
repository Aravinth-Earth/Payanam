#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# Desktop import loop: build -> launch -> import a database -> confirm, in one command.
#
# WHY THIS SHAPE. A headed run cannot be VERIFIED on a Wayland session: the app is an XWayland client and
# xdotool can enumerate its windows, but every X11 screen grab comes back uniform, so a click cannot be
# aimed. The one step that needs eyes is the FILE PICKER. Everything after it - the copy, the validation,
# the passphrase, the session, the preference read, the startup gate - is plain Kotlin behind the
# `DocumentSource` port, and that is what this loop exercises:
#
#   step 1  build the .deb (detekt, tests, coverage, package, retention - one pipeline every run)
#   step 2  launch the SHIPPED binary from an unpacked copy of that .deb, with an isolated XDG_DATA_HOME,
#           and read ITS OWN log to confirm the surface it chose (this is the launch half, headed but blind)
#   step 3  drive the real import through the real shared ViewModel with a real database, stopping only at
#           the picker seam, and assert the four facts the startup gate consumes
#   step 4  print one verdict block
#
# The passphrase is passed as a FILE PATH and read inside the harness. It is never printed, never echoed,
# never an argument value. Report its length only.
#
# Usage:
#   build-tools/scripts/desktop-import-loop.sh --db <encrypted.db> --pwd <passphrase-file> [options]
#
# Options:
#   --db <path>          the encrypted database to import          (required)
#   --pwd <path>         the file holding its passphrase          (required; last non-empty line is the secret)
#   --data-root <path>   where the run's app data goes            (default: a fresh scratch dir)
#   --skip-build         reuse the newest .deb already in output/debs
#   --reuse-deb <path>   drive this .deb instead of the newest    (implies --skip-build)
#   --skip-launch        do not launch the app; only run the import harness
#
# Exit: 0 only when the build, the launch and the harness all pass.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

SCRATCH="${PAYANAM_IMPORT_SCRATCH:-${TMPDIR:-${HOME}/.cache}/payanam-import-loop}"
LOGS_DIR="$REPO_ROOT/build-logs"
mkdir -p "$SCRATCH" "$LOGS_DIR"

DB=""
PWD_FILE=""
DATA_ROOT=""
SKIP_BUILD=0
SKIP_LAUNCH=0
REUSE_DEB=""
REUSE_DEB_GIVEN=0

while [[ $# -gt 0 ]]; do
    case "$1" in
    --db) DB="${2:-}"; shift 2 ;;
    --pwd) PWD_FILE="${2:-}"; shift 2 ;;
    --data-root) DATA_ROOT="${2:-}"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --reuse-deb) REUSE_DEB="${2:-}"; REUSE_DEB_GIVEN=1; shift 2 ;;
    --skip-launch) SKIP_LAUNCH=1; shift ;;
    -h | --help) sed -n '3,34p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DB" ]] || { echo "ERROR: --db is required" >&2; exit 2; }
[[ -n "$PWD_FILE" ]] || { echo "ERROR: --pwd is required" >&2; exit 2; }
[[ -f "$DB" ]] || { echo "ERROR: database not found: $DB" >&2; exit 2; }
[[ -f "$PWD_FILE" ]] || { echo "ERROR: passphrase file not found: $PWD_FILE" >&2; exit 2; }
if [[ "$REUSE_DEB_GIVEN" -eq 1 ]]; then
    # A named .deb is the whole point of this option: the run must exercise the artifact the caller
    # chose, not whatever happens to be newest. Validated the same way --db and --pwd are - exist,
    # then suffix. --skip-build is implied rather than rejected: naming the artifact IS the skip, and
    # accepting the pair quietly would let the log claim a build that never ran.
    [[ -n "$REUSE_DEB" ]] || { echo "ERROR: --reuse-deb needs a .deb path" >&2; exit 2; }
    [[ -f "$REUSE_DEB" ]] || { echo "ERROR: .deb not found: $REUSE_DEB" >&2; exit 2; }
    [[ "$REUSE_DEB" == *.deb ]] || { echo "ERROR: --reuse-deb needs a .deb path, got: $REUSE_DEB" >&2; exit 2; }
    REUSE_DEB="$(realpath "$REUSE_DEB")"
    SKIP_BUILD=1
fi

STAMP="$(date +%Y%m%d_%H%M%S)"
[[ -n "$DATA_ROOT" ]] || DATA_ROOT="$SCRATCH/data-root-$STAMP"
VERDICT_LOG="$LOGS_DIR/desktop-import-loop-$STAMP.log"

step() { printf '\n=== %s ===\n' "$1" | tee -a "$VERDICT_LOG"; }
verdict() { printf '%s\n' "$1" | tee -a "$VERDICT_LOG"; }

# ---------------------------------------------------------------- step 1: build
if [[ "$SKIP_BUILD" -eq 1 ]]; then
    if [[ "$REUSE_DEB_GIVEN" -eq 1 ]]; then
        step "1/4 build: SKIPPED (--reuse-deb) - the named .deb, not a fresh one, is what this run proves"
    else
        step "1/4 build: SKIPPED (--skip-build)"
    fi
else
    step "1/4 build: bash build-tools/scripts/build-linux.sh"
    if ! bash build-tools/scripts/build-linux.sh >"$LOGS_DIR/desktop-import-loop-build-$STAMP.log" 2>&1; then
        verdict "LOOP-FAILED: the build failed - see $LOGS_DIR/desktop-import-loop-build-$STAMP.log"
        exit 1
    fi
    # The build log must carry the published artefact line; the naming check fails the loop without it.
    grep -aE "Desktop verification passed|Published DEB: output/debs/Payanam_Linux_" \
        "$LOGS_DIR/desktop-import-loop-build-$STAMP.log" |
        tail -2 | tee -a "$VERDICT_LOG"
    grep -aqF "Published DEB: output/debs/Payanam_Linux_" \
        "$LOGS_DIR/desktop-import-loop-build-$STAMP.log" ||
        { verdict "LOOP-FAILED: the build log names no Payanam_Linux_<version>_<sha>_<stamp>.deb"; exit 1; }
fi

# ---------------------------------------------------------------- step 2: launch
LAUNCH_OK="skipped"
if [[ "$SKIP_LAUNCH" -eq 0 ]]; then
    step "2/4 launch: the shipped binary, isolated data root, its own log as the witness"
    if pgrep -x Payanam >/dev/null 2>&1; then
        # Never kill a process this loop did not start. The app's instance lock is machine-wide, so a
        # pre-existing instance would answer instead of ours and the run would be a false one.
        LAUNCH_OK="blocked: another Payanam instance is already running (close it, then re-run)"
        verdict "LOOP-NOTE: $LAUNCH_OK"
    else
        IMAGE="$SCRATCH/image-$STAMP"
        if [[ -n "$REUSE_DEB" ]]; then
            DEB="$REUSE_DEB"
        else
            DEB="$(ls -t "$REPO_ROOT"/output/debs/Payanam_Linux_*.deb 2>/dev/null | head -1 || true)"
        fi
        if [[ -z "$DEB" ]]; then
            LAUNCH_OK="blocked: no .deb in output/debs"
            verdict "LOOP-NOTE: $LAUNCH_OK"
        else
            # Name the artifact and its age in the log. Every later line describes THAT binary, and a
            # .deb older than the tree under test would otherwise be indistinguishable from a fresh one.
            verdict "LOOP-DEB: $DEB (built $(date -r "$DEB" '+%Y-%m-%d %H:%M:%S'))"
            mkdir -p "$IMAGE"
            dpkg -x "$DEB" "$IMAGE"
            LAUNCHER="$(find "$IMAGE" -type f -path '*/bin/Payanam' | head -1 || true)"
            if [[ -z "$LAUNCHER" ]]; then
                LAUNCH_OK="blocked: no launcher inside $DEB"
                verdict "LOOP-NOTE: $LAUNCH_OK"
            else
                rm -rf "$DATA_ROOT"
                mkdir -p "$DATA_ROOT"
                XDG_DATA_HOME="$DATA_ROOT" nohup "$LAUNCHER" >"$SCRATCH/app-stdout-$STAMP.log" 2>&1 &
                APP_PID=$!
                sleep 20
                # The log goes where the APP decides, not where this script would guess. Mirroring
                # DesktopAppPaths: with XDG_DATA_HOME set it replaces <home> as the parent, and the root
                # name carries a "-debug" suffix on a debug build ("$APP_DIRECTORY_NAME-debug"). This .deb
                # was built with build-linux.sh's default BUILD_TYPE=debug, so Payanam-debug is the root
                # actually in use - a sweep that only knew the unsuffixed name found nothing under an
                # otherwise completely healthy run, the same way desktop-loop.sh did.
                # BOTH names are searched anyway: --reuse-deb/--skip-build can hand over a binary built
                # the other way, and the debug flag is frozen in at build time. Newest wins.
                APP_LOG=""
                for root_name in "Payanam-debug" "Payanam"; do
                    # `|| true` is load-bearing under this script's `set -euo pipefail`: head closes the
                    # pipe on the first line, sort takes SIGPIPE and reports non-zero, and a bare
                    # assignment would abort the whole run over a successful find.
                    candidate="$(find "$DATA_ROOT/Documents/$root_name/logs" -maxdepth 1 -type f \
                        -name 'payanam_desktop_*.log' ! -name 'payanam_desktop_latest_export.log' \
                        -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2- || true)"
                    [[ -n "$candidate" ]] || continue
                    if [[ -z "$APP_LOG" || "$candidate" -nt "$APP_LOG" ]]; then
                        APP_LOG="$candidate"
                    fi
                done
                # Nothing in the app's own layout: fall back to a sweep of the whole isolated data root,
                # which cannot miss an unexpected layout but is also why the rule above is tried first.
                if [[ -z "$APP_LOG" ]]; then
                    APP_LOG="$(find "$DATA_ROOT" -type f -name 'payanam_desktop_*.log' \
                        ! -name 'payanam_desktop_latest_export.log' -printf '%T@ %p\n' 2>/dev/null \
                        | sort -rn | head -1 | cut -d' ' -f2- || true)"
                fi
                if [[ -n "$APP_LOG" ]]; then
                    SURFACE="$(grep -a 'surfaceMounted' "$APP_LOG" | tail -1 | sed 's/ *$//' | cut -c1-160 || true)"
                    LAUNCH_OK="up (pid $APP_PID), it chose: ${SURFACE:-<no surfaceMounted line yet>}"
                else
                    LAUNCH_OK="up (pid $APP_PID) but wrote no log under $DATA_ROOT (looked in $DATA_ROOT/Documents/Payanam{,-debug}/logs)"
                fi
                verdict "LOOP-LAUNCH: $LAUNCH_OK"
                kill "$APP_PID" 2>/dev/null || true
                wait "$APP_PID" 2>/dev/null || true
            fi
        fi
    fi
else
    step "2/4 launch: SKIPPED (--skip-launch)"
fi

# ---------------------------------------------------------------- step 3: import
step "3/4 import: the real shared ViewModel, stopping at the picker seam"
# The build's test task does not forward CLI -D properties into the test JVM, and loop-only configuration
# does not belong in the product's build file, so it is injected with an init script instead.
INIT="$SCRATCH/loop-init-$STAMP.gradle"
cat >"$INIT" <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        ['payanam.loop.db', 'payanam.loop.pwd', 'payanam.loop.dataRoot'].each { key ->
            if (System.getProperty(key) != null) systemProperty(key, System.getProperty(key))
        }
    }
}
GRADLE

HARNESS_LOG="$LOGS_DIR/desktop-import-loop-harness-$STAMP.log"
set +e
./gradlew -I "$INIT" :desktop:test --console=plain \
    -Dpayanam.loop.db="$(realpath "$DB")" \
    -Dpayanam.loop.pwd="$(realpath "$PWD_FILE")" \
    -Dpayanam.loop.dataRoot="$DATA_ROOT" >"$HARNESS_LOG" 2>&1
HARNESS_EXIT=$?
set -e

# Gradle hides test stdout, so the verdict is read from the result XML.
HARNESS_VERDICT="$(python3 - "$REPO_ROOT" <<'PY'
import glob, html, re, sys
repo = sys.argv[1]
for path in glob.glob(f"{repo}/desktop/build/test-results/test/TEST-*.xml"):
    if "DesktopImportLoopHarnessTest" not in path:
        continue
    text = open(path).read()
    out = re.search(r"<system-out>(.*?)</system-out>", text, re.S)
    if out:
        # Gradle wraps the captured stdout in CDATA; strip the markers so the verdict prints as one line.
        body = html.unescape(out.group(1)).replace("<![CDATA[", "").replace("]]>", "")
        for line in body.splitlines():
            if "LOOP-VERDICT" in line or "LOOP-EVIDENCE" in line:
                print(line.strip())
PY
)"
COUNTS="$(python3 - "$REPO_ROOT" <<'PY'
import glob, re, sys
tot = fail = skip = 0
for path in glob.glob(f"{sys.argv[1]}/desktop/build/test-results/test/TEST-*.xml"):
    m = re.search(r'tests="(\d+)"[^>]*skipped="(\d+)"[^>]*failures="(\d+)"', open(path).read())
    if m:
        tot += int(m.group(1)); skip += int(m.group(2)); fail += int(m.group(3))
print(f"desktop tests={tot} failures={fail} skipped={skip}")
PY
)"

# ---------------------------------------------------------------- step 4: verdict
step "4/4 verdict"
[[ -n "$HARNESS_VERDICT" ]] && verdict "$HARNESS_VERDICT" || verdict "LOOP-FAILED: the harness produced no verdict (0 tests matched? see $HARNESS_LOG)"
verdict "LOOP-COUNTS: $COUNTS"
verdict "LOOP-LAUNCH: $LAUNCH_OK"
verdict "LOOP-DATA-ROOT: $DATA_ROOT"

if [[ "$HARNESS_EXIT" -ne 0 || -z "$HARNESS_VERDICT" ]]; then
    verdict "LOOP-RESULT: RED - the import flow did not verify (harness exit $HARNESS_EXIT)"
    exit 1
fi
verdict "LOOP-RESULT: GREEN - the database imported, the session opened, the rows were read"
