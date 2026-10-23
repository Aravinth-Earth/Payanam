#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
# ProGuard rules for the Compose Desktop release distribution (:desktop).
#
# WHY THIS FILE EXISTS
# The bundled SQLite JDBC driver (org.xerial:sqlite-jdbc) looks for SLF4J reflectively:
# org.sqlite.util.LoggerFactory runs Class.forName("org.slf4j.Logger") in its static initializer
# and, only when that succeeds, builds its nested org.sqlite.util.LoggerFactory$SLF4JLogger - the
# one class that references org.slf4j.Logger and org.slf4j.LoggerFactory directly.
#
# The desktop ships NO SLF4J binding, so that optional lookup legitimately fails at runtime and
# the driver falls back to its own java.util.logging logger
# (org.sqlite.util.LoggerFactory$JDKLogger). The missing classes are therefore NOT a packaging
# defect, and the fix is deliberately NOT an slf4j dependency: this app ships no SLF4J and must
# keep shipping none.
#
# ProGuard cannot see the try/catch fallback, so it reports those 12 references as unresolved and
# aborts the release build with "Please correct the above warnings first". The rules below
# suppress exactly those warnings and nothing else.
#
# Narrow on purpose: no blanket -dontwarn, and no optimizer or obfuscator setting is switched off,
# so the release distribution is still shrunk and optimized.

# ---- SLF4J: optional, probed reflectively by the SQLite JDBC driver ----
-dontwarn org.slf4j.**
# The same optional lookup, reported as a note rather than a warning. Notes never fail the build;
# -dontnote only keeps the release log free of a misleading "missing class" line.
-dontnote org.slf4j.**
