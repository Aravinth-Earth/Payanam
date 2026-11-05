# Drive script for build-tools/scripts/desktop-loop.sh (-d this file) - PHASE 2 of the unlock-gate pair.
#
# WHAT THIS EXISTS FOR
# With a database already on disk, a launch resolves to the PASSPHRASE UNLOCK gate instead of asking to
# create one. This file photographs that gate. It deliberately types nothing and clicks nothing: the
# question being answered is "which gate is on screen, and are the log-export buttons on it", and a
# passphrase attempt would move the app off the gate and destroy the evidence.
#
# WHY THIS MUST RUN WITHOUT -f
# `-f` wipes the loop's $HOME, and the loop's $HOME is where phase 1 created the database. Wiping it
# would throw away the only thing that makes the unlock gate reachable, and the run would land back on
# the setup gate - which is the exact confusion these two scripts exist to prevent. So phase 2 is run as
#   bash build-tools/scripts/desktop-loop.sh -d build-tools/scripts/desktop-loop-drive-unlock.sh
# with no -f, against the same HOME phase 1 used (LOOP_HOME defaults to the same path in both).
#
# THE PROOF IS IN THE LOG, NOT THE PICTURE
# A screenshot alone cannot distinguish "the unlock gate" from "the setup gate" if both look similar,
# so the run's real evidence is the log line this gate is identified by:
#   DesktopApp.desktopStartupMode  ->  [startupMode="UnlockPassphrase"]
#   DesktopApp.surfaceMounted      ->  [graphOwnsGate=true, ..., shouldShowPassphraseUnlock=true]
# desktop-loop.sh already prints both, because it tails the app's own log. Read them, do not infer the
# mode from the frame.
#
# The helpers come from the loop script: shot NAME, sleep N.

# The loop has already photographed its own first screen (00-first-screen.png) by the time this file
# runs. This file's job is the settled frame: the gate finishes composing and running its startup
# checks after the window appears, so the first frame can still be the previous surface.
sleep 8
shot 01-unlock-gate-settled

# A second frame, taken later, so a gate that arrives late still appears, and so a stable screen can be
# told apart from one that was still painting. Identical bytes across the two would mean the screen had
# stopped moving, which is the state a photograph of a finished gate should be in.
sleep 8
shot 02-unlock-gate-final
