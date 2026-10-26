#!/usr/bin/env bash
# ============================================================================
#  Payanam Desktop Build Script — Linux (Compose JVM)
#  Artifact: output/debs/Payanam_linux_<version>_<sha>_<debug|release>_<yyyyMMdd_HHmmss>.deb
#            output/debs/<same name>.deb.sha256
#            (flat folder, mirroring Android's output/apks layout)
#  Retention: keep the newest 5 .debs total, delete older; each .deb's .sha256 rides with it.
# ============================================================================
set -euo pipefail

# ── Defaults ────────────────────────────────────────────────────────────────
INSTALL_DEB=false
RELEASE=false
MAX_ARTIFACTS=5

# ── Parse args ──────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --install-deb) INSTALL_DEB=true; shift ;;
        --release)     RELEASE=true; shift ;;
        --help)
            echo "Usage: $0 [--install-deb] [--release]"
            echo "  --install-deb  install the built .deb with dpkg (default: not installed)"
            echo "  --release      build the release distributable"
            echo "  --help         print this text"
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# ── Resolve project root ───────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# ── Helpers ─────────────────────────────────────────────────────────────────
log()  { echo "[$(date '+%H:%M:%S')] $*"; }
info() { log "$*"; }
ok()   { log "  ✅ $*"; }
warn() { log "  ⚠️  $*"; }
fail() { log "  ❌ $*"; exit 1; }

datetime_stamp() { date '+%Y%m%d_%H%M%S'; }
local_datetime() { date '+%Y-%m-%dT%H:%M:%S%z'; }

# shellcheck source=/dev/null
. "$SCRIPT_DIR/lib/naming.sh"

# Write JSON file preserving formatting
json_write() {
    local file="$1"
    python3 -c "
import json, sys
with open('$file') as f:
    d = json.load(f)
# Execute updates from stdin
exec(sys.stdin.read())
with open('$file', 'w') as f:
    json.dump(d, f, indent=4)
    f.write('\n')
"
}

# sed-in-place (GNU sed on Linux)
sed_i() {
    sed -i "$@"
}

# ── Colours (if terminal supports it) ──────────────────────────────────────
if [[ -t 1 ]]; then
    CYAN='\033[0;36m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'
    RED='\033[0;31m'; MAGENTA='\033[0;35m'; NC='\033[0m'
else
    CYAN=''; GREEN=''; YELLOW=''; RED=''; MAGENTA=''; NC=''
fi

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Payanam Desktop Build (Linux)${NC}"
echo -e "${CYAN}========================================${NC}"

# This script targets Linux. Fail immediately if not on Linux.
if [[ "$(uname -s)" != "Linux" ]]; then
    fail "This script targets Linux but is running on $(uname -s). Use build-windows.ps1 for Windows builds."
fi

BUILD_TYPE="debug"
$RELEASE && BUILD_TYPE="release"
info "Build Type: $BUILD_TYPE"

# Install is opt-in: the default run never touches the installed app.
if $INSTALL_DEB; then
    info "Install: enabled (--install-deb)"
else
    info "Install: disabled (default)"
fi

# ════════════════════════════════════════════════════════════════════════════
#  PREFLIGHT CHECKS
# ════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${MAGENTA}=== PREFLIGHT CHECKS ===${NC}"

# Java
info "Checking Java version..."
if [[ -n "${JAVA_HOME:-}" ]]; then
    info "  JAVA_HOME: $JAVA_HOME"
else
    warn "  JAVA_HOME not set; Gradle may fall back to system Java."
fi
java_version=$(java -version 2>&1 | head -1 || echo "not found")
info "  Java: $java_version"

# Gradle wrapper
info "Checking Gradle wrapper..."
[[ -f "./gradlew" ]] || fail "Gradle wrapper missing (./gradlew)"
chmod +x ./gradlew
ok "Gradle wrapper present"

# ── Temp directory capacity (must run BEFORE any Gradle invocation) ───────────
# Gradle, the Kotlin daemon, jpackage and dpkg-deb all stage large scratch files in the system
# temp directory. On a host where that directory is a small tmpfs with a per-user quota, those
# writes fail with "Disk quota exceeded" and the build dies for ENVIRONMENT reasons - there is
# no code defect anywhere to fix. Probe the temp directory ONCE, before Gradle starts, and move
# the whole run onto a bigger filesystem when it cannot hold the scratch space. On a healthy
# machine this changes NOTHING: no env var is touched, no daemon is stopped, and the only
# output is one line.
TMP_PROBE_BYTES=$((64 * 1024 * 1024))
TMP_PROBE_DIR="${TMPDIR:-/tmp}"

# Writes zeros into the directory and removes the probe file again in EVERY path. Returns 0 only
# when the whole amount landed on disk: a quota-exceeded write fails part-way through and leaves
# a short file behind, which is exactly the case this has to catch.
tmp_probe_capacity() {
    local dir="$1" required="$2"
    local probe_file="$dir/payanam-tmp-probe.$$"
    local count=$(( required / 1024 / 1024 ))
    local written=0
    rm -f "$probe_file" 2>/dev/null || true
    if ! dd if=/dev/zero of="$probe_file" bs=1M count="$count" status=none 2>/dev/null; then
        rm -f "$probe_file" 2>/dev/null || true
        return 1
    fi
    written=$(stat -c %s "$probe_file" 2>/dev/null || echo 0)
    rm -f "$probe_file" 2>/dev/null || true
    [[ "$written" -eq "$required" ]]
}

info "Checking temp directory capacity..."
if tmp_probe_capacity "$TMP_PROBE_DIR" "$TMP_PROBE_BYTES"; then
    info "  Temp directory usable: $TMP_PROBE_DIR"
else
    TMP_FALLBACK="${PAYANAM_BUILD_TMP:-/var/tmp/payanam-build-tmp}"
    if ! mkdir -p "$TMP_FALLBACK" 2>/dev/null; then
        fail "Temp directory $TMP_PROBE_DIR failed the 64 MB write probe and the fallback $TMP_FALLBACK could not be created. Set PAYANAM_BUILD_TMP to a writable directory on a larger filesystem."
    fi
    export TMPDIR="$TMP_FALLBACK"
    if [[ -n "${JAVA_TOOL_OPTIONS:-}" ]]; then
        export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djava.io.tmpdir=$TMP_FALLBACK"
    else
        export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMP_FALLBACK"
    fi
    # A daemon started before the probe still holds the OLD temp dir, so stop it: the next build
    # then starts a fresh daemon that picks the new one up.
    ./gradlew --stop >/dev/null 2>&1 || true
    warn "  Temp directory probe FAILED for $TMP_PROBE_DIR (64 MB write); using $TMP_FALLBACK for this run."
fi

# Source structure
info "Checking desktop source structure..."
MISSING=()
for f in \
    "desktop/build.gradle.kts" \
    "desktop/src/main/kotlin/io/payanam/desktop/DesktopMain.kt" \
    "desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt"; do
    [[ -f "$f" ]] || MISSING+=("$f")
done
if [[ ${#MISSING[@]} -gt 0 ]]; then
    fail "Missing desktop source files: ${MISSING[*]}"
fi
ok "Desktop source structure present"

ok "All preflight checks passed!"

# ════════════════════════════════════════════════════════════════════════════
#  BUILD IDENTITY
# ════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${MAGENTA}=== BUILD IDENTITY ===${NC}"

# gradle/version.properties is the only version source; this script never computes or bumps it.
VERSION_FILE="gradle/version.properties"
[[ -f "$VERSION_FILE" ]] || fail "Version file not found: $VERSION_FILE"

PAYANAM_VERSION=$(sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*//p' "$VERSION_FILE" | head -1 | tr -d '\r' || true)
[[ -n "$PAYANAM_VERSION" ]] || fail "version= is missing from $VERSION_FILE"

# 'nogit' keeps source-tarball and extracted-zip builds working.
SHORT_SHA=$(git rev-parse --short HEAD 2>/dev/null || true)
[[ -n "$SHORT_SHA" ]] || SHORT_SHA="nogit"

DATE_TIME_STAMP=$(datetime_stamp)
GRADLE_PROPS="-PbuildPlatform=Linux -PbuildTimestamp=${DATE_TIME_STAMP} -PbuildShortSha=${SHORT_SHA} -PbuildType=${BUILD_TYPE}"
BUILD_NAME="$(payanam_generated_name "Payanam" "Linux" "$PAYANAM_VERSION" "$SHORT_SHA" "$BUILD_TYPE" "$DATE_TIME_STAMP")"

info "Version: $PAYANAM_VERSION (sha $SHORT_SHA)"
info "Build Name: $BUILD_NAME"

# ════════════════════════════════════════════════════════════════════════════
#  FORMATTING
# ════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${MAGENTA}=== FORMATTING ===${NC}"
# spotless-ratchet.ps1 checks only the files the change touches; never spotlessApply, which mutates the tree.
if pwsh "$SCRIPT_DIR/spotless-ratchet.ps1"; then
    ok "Formatting ratchet passed (changed files are formatted)"
else
    fail "Formatting ratchet failed - the files above are not formatted."
fi

# ════════════════════════════════════════════════════════════════════════════
#  DESKTOP VERIFICATION
# ════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${MAGENTA}=== DESKTOP VERIFICATION ===${NC}"
# Formatting is the ratchet's job (see the FORMATTING step); the module has a pre-existing
# `:desktop:spotlessCheck` backlog, so a clean checkout fails that task.
# :core:ui:desktopTest is named by no other task in this repo: the module's 154 tests (24
# classes, commonTest + desktopTest) would otherwise run in no gate at all, so a shared-screen
# regression there would ship unverified. It is the same JVM as :desktop:test and needs no
# Android compile.
#
# ── Added-source-without-test ratchet ──────────────────────────────────────────────────────
# No Gradle task can enforce "a new desktop/src/main class ships with a test or a stated
# reason": detekt and :desktop:test only ever see the tree as a whole, and jacoco coverage
# verification has a floor rather than a per-change rule. So it is enforced here, in the one
# gate that runs immediately before the desktop test task, using git + shell only (milliseconds,
# no Gradle startup).
#
# BASELINE, resolved in this order:
#   1. $PAYANAM_DESKTOP_TEST_BASELINE - any git ref or SHA. Set it to audit a WIDER range than
#      the new work, e.g. PAYANAM_DESKTOP_TEST_BASELINE=origin/dev.
#   2. The commit that first added THIS line to this file. Self-describing, and immune to the
#      pinning problem of a hardcoded ref that later moves, is deleted, or is not fetched.
# Anything else - unset with no marker commit yet, a ref that no longer resolves, a checkout with
# no git history at all - makes this a logged no-op. It must never be able to fail a build for a
# reason unrelated to the code being built.
#
# It is a RATCHET, not a cleanup pass. With the default baseline the range is "added since this
# check existed", which is empty on a tree that already satisfies it. The pre-existing uncovered
# desktop/src/main files (DesktopLogExportActions.kt, DesktopPassphraseSetupViewModel.kt and 12
# others) predate the gate and are named in the tracker; they are the backlog's business, not this
# check's. Widening the baseline to origin/dev surfaces all 14 today, and that run is a useful
# audit - it just does not belong in the build gate, where it would block every build.
#
# A file is COVERED when <Basename>Test.kt exists anywhere under desktop/src/test. The pairing is
# by name, not by mirrored package path: this module's tests do not mirror main's package layout.
#
# STATED-REASON MARKER: a line matching `// no-test: <reason>` anywhere in the file. Put it in
# the file's header comment so the reason sits with the code it excuses.
added_main_files() {
    {
        git diff --diff-filter=A --name-only "${BASELINE_REF}..HEAD" -- desktop/src/main
        git diff --cached --diff-filter=A --name-only -- desktop/src/main
        git ls-files --others --exclude-standard -- desktop/src/main
    } 2>/dev/null | grep '\.kt$' | sort -u || true
}

BASELINE_MARKER='PAYANAM_DESKTOP_TEST_BASELINE'
BASELINE_REF="${PAYANAM_DESKTOP_TEST_BASELINE:-}"
BASELINE_SOURCE="env"
if [[ -z "$BASELINE_REF" ]]; then
    BASELINE_REF=$(git log -S"$BASELINE_MARKER" --reverse --format=%H \
        -- build-tools/scripts/build-linux.sh 2>/dev/null | head -1 || true)
    BASELINE_SOURCE="this-check's first commit"
fi

if [[ -z "$BASELINE_REF" ]]; then
    info "  Added-source test ratchet: no baseline (no env var, no marker commit yet) - skipped."
elif ! git rev-parse --verify -q "${BASELINE_REF}^{commit}" >/dev/null 2>&1; then
    info "  Added-source test ratchet: baseline '$BASELINE_REF' does not resolve to a commit - skipped."
else
    ADDED_MAIN=$(added_main_files)
    ADDED_COUNT=$(printf '%s\n' "$ADDED_MAIN" | grep -c . || true)
    if [[ "$ADDED_COUNT" -eq 0 ]]; then
        info "  Added-source test ratchet: no .kt files added under desktop/src/main vs $BASELINE_REF ($BASELINE_SOURCE) - nothing to check."
    else
        TEST_BASENAMES=$(find desktop/src/test -type f -name '*Test.kt' -printf '%f\n' 2>/dev/null | sort -u || true)
        UNCOVERED=()
        while IFS= read -r added_file; do
            [[ -n "$added_file" ]] || continue
            added_name="$(basename "$added_file" .kt)"
            if printf '%s\n' "$TEST_BASENAMES" | grep -qx -- "${added_name}Test.kt"; then
                continue
            fi
            if [[ -f "$added_file" ]] && grep -qE '^[[:space:]]*//[[:space:]]*no-test:[[:space:]]*\S' "$added_file"; then
                ok "  $added_name.kt - stated reason ('// no-test:')"
                continue
            fi
            UNCOVERED+=("$added_file")
        done <<< "$ADDED_MAIN"

        if [[ ${#UNCOVERED[@]} -gt 0 ]]; then
            log "  ❌ ${#UNCOVERED[@]} added desktop/src/main source(s) with no matching *Test.kt under desktop/src/test:"
            for offender in "${UNCOVERED[@]}"; do
                log "       $offender"
            done
            fail "Desktop added-source test ratchet failed. Add a test, or a '// no-test: <reason>' line naming why there is none. To widen or narrow the range: PAYANAM_DESKTOP_TEST_BASELINE=<ref>."
        fi
        ok "Added-source test ratchet passed ($ADDED_COUNT added file(s) vs $BASELINE_REF)"
    fi
fi

# ── The SHARED modules are named here too, and a ratchet keeps the list honest ────────────────
# `:desktop:test` alone verifies the desktop module; the shared modules the desktop consumes
# (`core/domain`, `core/shared`, `core/scoring`, `core/presentation`, `core/database-jvm`, ...) were
# named by no task in THIS script, so a desktop build could go green while the code both platforms
# share was broken. The Android script names them, but a desktop-only run must too - "the desktop
# build passed" has to mean the shared code passed.
#
# The list is explicit so it is auditable; the ratchet below compares it against every module that
# HAS a src/test and fails on a gap, so it cannot silently fall behind the tree.
SHARED_TEST_TASKS=":core:backup:test :core:database-jvm:test :core:database-model:test :core:domain:test :core:presentation:test :core:scoring:test :core:shared:test :core:strings:test"
NAMED_MODULES="desktop core/backup core/database-jvm core/database-model core/domain core/presentation core/scoring core/shared core/strings"
# Android-only libraries: their unit tests need the Android plugin and the Android SDK, and this
# script is the DESKTOP phase. They are named by build-android.ps1, which every all-platforms run
# executes first - so excluding them here is not a gap, it is the correct split.
ANDROID_ONLY_MODULES="core/common core/database"
MISSING_MODULES=""
for module_dir in core/* desktop; do
    [ -d "$module_dir/src/test" ] || continue
    module_name="$module_dir"
    case " $ANDROID_ONLY_MODULES " in
        *" $module_name "*) continue ;;
    esac
    case " $NAMED_MODULES " in
        *" $module_name "*) ;;
        *) MISSING_MODULES="$MISSING_MODULES $module_name" ;;
    esac
done
if [ -n "$MISSING_MODULES" ]; then
    fail "Test ratchet: these modules have a src/test but are named by no task:$MISSING_MODULES - add each to SHARED_TEST_TASKS/NAMED_MODULES above, or its tests run in no gate."
fi
ok "Shared-module test ratchet passed (every module with src/test is named)"

if ./gradlew :desktop:detekt :desktop:test \
        :core:ui:desktopTest \
        $SHARED_TEST_TASKS \
        :desktop:jacocoTestReport :desktop:jacocoTestCoverageVerification \
        $GRADLE_PROPS --no-configuration-cache --console=plain 2>&1; then
    ok "Desktop verification passed (detekt, tests, coverage)"
    info "Coverage report: desktop/build/reports/jacoco/test/html/index.html"
else
    fail "Desktop verification failed!"
fi

# ════════════════════════════════════════════════════════════════════════════
#  BUILDING LINUX PACKAGE
# ════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${MAGENTA}=== BUILDING LINUX PACKAGE ===${NC}"

PACKAGE_TASKS=":desktop:createDistributable :desktop:packageDistributionForCurrentOS"
$RELEASE && PACKAGE_TASKS=":desktop:createReleaseDistributable :desktop:packageReleaseDistributionForCurrentOS"

if ./gradlew $PACKAGE_TASKS $GRADLE_PROPS --no-configuration-cache --console=plain 2>&1; then
    ok "Desktop packaging passed"
else
    fail "Desktop packaging failed!"
fi

COMPOSE_OUTPUT="desktop/build/compose/binaries"
[[ -d "$COMPOSE_OUTPUT" ]] || fail "Desktop compose output not found at $COMPOSE_OUTPUT"

# ── Copy the installer to the flat artifact folder ──────────────────────────
# The .deb is the ONLY artifact kept per build, and it lives in a flat folder exactly like Android's
# output/apks. jpackage also produces an extracted copy of the app under compose/binaries/main/app -
# that copy is NOT published: the .deb already contains the same payload, so keeping both would
# duplicate ~139 MB per build for nothing.
#
# The app tree is still produced inside desktop/build/compose/binaries for anyone who wants to run the
# app without installing; it is just not copied into output/.
DEB_OUTPUT_DIR="output/debs"
mkdir -p "$DEB_OUTPUT_DIR"

# jpackage renders the package as payanam_<packageVersion>-1_amd64.deb, a string Gradle config cannot
# reshape, so the file is renamed to Payanam_linux_<version>_<sha>_<debug|release>_<yyyyMMdd_HHmmss>.deb.
#
# The Debian control fields (Package:, Version:) are NOT touched - renaming a .deb does not change what
# dpkg installs or upgrades.
DEB_SOURCE=$(find "$COMPOSE_OUTPUT" -name "*.deb" -type f 2>/dev/null | head -1)

# ── StartupWMClass, without which the DOCK entry is wrong ─────────────────────────────────────────
# GNOME ties a RUNNING window to its launcher entry by matching the window's WM_CLASS against the
# entry's StartupWMClass. jpackage does not write that key, and the entry looked fine anyway in every
# path that does not need it: search matches on Name and renders Icon= correctly, so the miss was
# invisible until the dock - which has nothing to match the window to, and shows a generic icon.
#
# Measured on the running app (`xprop -id <win> WM_CLASS`), the class is the application's main class
# with dots replaced by dashes:
#   io.payanam.desktop.DesktopMainKt  ->  io-payanam-desktop-DesktopMainKt
# So it is DERIVED from the Gradle config rather than hardcoded here: renaming the main class keeps
# this correct instead of silently breaking the dock again.
#
# Adding the key means repacking the .deb. Only /opt/.../<name>.desktop changes, so DEBIAN/md5sums is
# regenerated to stay truthful; the ~200 MB payload is copied through otherwise untouched.
MAIN_CLASS=$(grep -oE 'mainClass = "[^"]+"' "$PROJECT_ROOT/desktop/build.gradle.kts" 2>/dev/null | head -1 | cut -d'"' -f2)
WM_CLASS="${MAIN_CLASS//./-}"
if [[ -n "$DEB_SOURCE" && -n "$WM_CLASS" ]]; then
    REPACK_DIR=$(mktemp -d)
    PATCHED_DEB=$(mktemp --suffix=.deb)
    if dpkg-deb -R "$DEB_SOURCE" "$REPACK_DIR" 2>/dev/null; then
        DESKTOP_FILE=$(find "$REPACK_DIR" -name "*.desktop" ! -path "*legal*" 2>/dev/null | head -1)
        if [[ -z "$DESKTOP_FILE" ]]; then
            warn "  No .desktop entry found in the package; the dock icon will not match"
        elif grep -q "^StartupWMClass=" "$DESKTOP_FILE"; then
            info "  StartupWMClass already present ($(grep '^StartupWMClass=' "$DESKTOP_FILE" | cut -d= -f2))"
        else
            printf 'StartupWMClass=%s\n' "$WM_CLASS" >> "$DESKTOP_FILE"
            # Regenerate the integrity manifest for the changed payload, in dpkg's own format
            # (<md5>  <path relative to the package root, no leading ./>, sorted).
            if [[ -d "$REPACK_DIR/DEBIAN" ]]; then
                (cd "$REPACK_DIR" && find . -type f ! -path './DEBIAN/*' -printf '%P\n' | sort | xargs md5sum > DEBIAN/md5sums)
            fi
            if dpkg-deb -b "$REPACK_DIR" "$PATCHED_DEB" >/dev/null 2>&1; then
                cp "$PATCHED_DEB" "$DEB_SOURCE"
                ok "  StartupWMClass=$WM_CLASS added to the launcher entry"
            else
                warn "  Could not rebuild the .deb after adding StartupWMClass; dock icon will not match"
            fi
        fi
    else
        warn "  Could not unpack the .deb to add StartupWMClass; dock icon will not match"
    fi
    rm -rf "$REPACK_DIR" "$PATCHED_DEB"
fi
DEB_FILE=""
if [[ -z "$DEB_SOURCE" ]]; then
    warn "No .deb package found in $COMPOSE_OUTPUT"
else
    DEB_TARGET="$DEB_OUTPUT_DIR/${BUILD_NAME}.deb"
    if [[ -e "$DEB_TARGET" ]]; then
        # Never clobber: a same-named artifact means this build name was already published (an aborted
        # rerun, most likely), and silently replacing it would destroy evidence.
        warn "Refusing to overwrite existing artifact: $DEB_TARGET"
    else
        cp "$DEB_SOURCE" "$DEB_TARGET"
        DEB_FILE="$DEB_TARGET"
        DEB_SIZE=$(du -m "$DEB_FILE" | cut -f1)
        ok "Published DEB: $DEB_FILE (${DEB_SIZE} MB)"
        info "  (jpackage source: $(basename "$DEB_SOURCE"))"
        # Checksum sidecar, paired with the .deb the same way Android pairs .apk with .apk.sha256.
        # UPPERCASE hex, two-space separator, bare filename; `sha256sum -c` accepts either case.
        # (`443186189D81D530...E13C  Payanam_android_<version>_<sha>_<type>_<stamp>.apk`)
        (
            cd "$DEB_OUTPUT_DIR" &&
                sha256sum "${BUILD_NAME}.deb" | awk '{ print toupper($1) "  " $2 }' > "${BUILD_NAME}.deb.sha256"
        )
        ok "  Checksum: ${BUILD_NAME}.deb.sha256"
    fi
fi

# Find AppImage if present
APPIMAGE_FILE=$(find "$COMPOSE_OUTPUT" -name "*.AppImage" -type f 2>/dev/null | head -1)
if [[ -n "$APPIMAGE_FILE" ]]; then
    AI_SIZE=$(du -m "$APPIMAGE_FILE" | cut -f1)
    ok "Packaged AppImage: $APPIMAGE_FILE (${AI_SIZE} MB)"
fi

# ════════════════════════════════════════════════════════════════════════════
#  LINUX INSTALLATION
# ════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${MAGENTA}=== LINUX INSTALLATION ===${NC}"
if $INSTALL_DEB; then
    if [[ -n "$DEB_FILE" ]]; then
        info "Installing .deb package..."
        if dpkg -i "$DEB_FILE" 2>&1; then
            ok "Desktop .deb installed successfully"
        else
            warn "dpkg -i failed (may need sudo). Install with the EXACT path:"
            warn "  sudo dpkg -i $DEB_FILE"
        fi
    else
        warn "No .deb to install. Artifacts at: $DEB_OUTPUT_DIR"
    fi
else
    info "Skipping install. Artifacts at: $DEB_OUTPUT_DIR"
fi

# ════════════════════════════════════════════════════════════════════════════
#  ARTIFACT RETENTION
# ════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${MAGENTA}=== ARTIFACT RETENTION ===${NC}"
# Mirrors Android's apk retention: keep the newest N artifacts. The current build is protected by name,
# so it can never be a deletion candidate even if its mtime looks older than a sibling.
#
# Each artifact's .sha256 sidecar is deleted with it; an orphan sweep removes any sidecar whose .deb is gone.
retain_artifacts() {
    local dir="$1" keep="$2" current="$3"
    [[ -d "$dir" ]] || return 0
    local count
    count=$(find "$dir" -maxdepth 1 -mindepth 1 -type f -name "*.deb" | wc -l)
    local orphans
    orphans=$(count_orphan_sidecars "$dir")

    if (( count <= keep )); then
        info "  Retention skip: $dir ($count deb(s), keep=$keep)"
    else
        local protected="$dir/$current.deb"
        local to_delete
        to_delete=$(find "$dir" -maxdepth 1 -mindepth 1 -type f -name "*.deb" ! -path "$protected" -printf '%T@ %p\n' \
            | sort -rn \
            | awk 'NR>'"$((keep - 1))"'{print $2}')
        if [[ -z "$to_delete" ]]; then
            info "  Retention: nothing to delete in $dir (keep=$keep, current protected)"
        else
            while IFS= read -r victim; do
                [[ -n "$victim" ]] || continue
                rm -f "$victim"
                info "  Retention: removed $(basename "$victim")"
                # The sidecar is only meaningful next to the artifact it verifies.
                if [[ -f "$victim.sha256" ]]; then
                    rm -f "$victim.sha256"
                    info "  Retention: removed $(basename "$victim").sha256"
                fi
            done <<< "$to_delete"
            ok "  Retention applied on $dir (kept newest $keep deb(s), current included)"
        fi
    fi

    if (( orphans > 0 )); then
        local swept=0
        while IFS= read -r sidecar; do
            [[ -n "$sidecar" ]] || continue
            local owner="${sidecar%.sha256}"
            if [[ ! -f "$owner" ]]; then
                rm -f "$sidecar"
                swept=$((swept + 1))
            fi
        done < <(find "$dir" -maxdepth 1 -mindepth 1 -type f -name "*.deb.sha256")
        (( swept > 0 )) && ok "  Swept $swept orphaned checksum(s) with no matching .deb"
    fi
}

# Counts .sha256 sidecars whose owner artifact no longer exists.
count_orphan_sidecars() {
    local dir="$1" n=0 sidecar
    while IFS= read -r sidecar; do
        [[ -n "$sidecar" ]] || continue
        [[ -f "${sidecar%.sha256}" ]] || n=$((n + 1))
    done < <(find "$dir" -maxdepth 1 -mindepth 1 -type f -name "*.deb.sha256" 2>/dev/null)
    printf '%s' "$n"
}

retain_artifacts "$DEB_OUTPUT_DIR" "$MAX_ARTIFACTS" "$BUILD_NAME"

# ════════════════════════════════════════════════════════════════════════════
#  DONE
# ════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  DESKTOP BUILD COMPLETED SUCCESSFULLY!${NC}"
echo -e "${CYAN}========================================${NC}"
info "  Build Name: $BUILD_NAME"
info "  Version: $PAYANAM_VERSION (sha $SHORT_SHA)"
info "  Artifact Folder: $DEB_OUTPUT_DIR"
if [[ -n "${DEB_FILE:-}" ]]; then
    info "  DEB Path: $DEB_FILE"
    # The folder holds the .deb AND its .sha256, so a glob matches two files and dpkg rejects the
    # checksum as "not a Debian format archive". Use the exact path.
    info "  Install : sudo dpkg -i $DEB_FILE"
fi
[[ -n "${APPIMAGE_FILE:-}" ]] && info "  AppImage Path: $APPIMAGE_FILE"
echo -e "${CYAN}========================================${NC}"

# Every run ends with the daemons stopped.
./gradlew --stop 2>/dev/null || true

exit 0
