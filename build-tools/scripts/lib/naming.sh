#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
# Generated name: <prefix>_<platform>_<version>_<sha>_<type>_<timestamp>[.<ext>]
payanam_generated_name() {
    local prefix="$1" platform="$2" version="$3" sha="$4" type="$5" timestamp="$6"
    local token
    case "${platform,,}" in
        android | linux | windows | macos) token="${platform,,}" ;;
        *)
            printf 'unknown platform: %s\n' "$platform" >&2
            return 1
            ;;
    esac
    printf '%s_%s_%s_%s_%s_%s\n' "$prefix" "$token" "$version" "$sha" "$type" "$timestamp"
}
