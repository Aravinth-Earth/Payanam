# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#
# The measured first screen of a fresh Linux install is the PRIVACY TOUR, rendered in Tamil, page 1 of 3
# (three dot indicators), with the window at roughly x=50..458, y=50..850 of the 1280x900 X root. Two
# tappable controls on it, at these pixel centres:
#
#   அடுத்து        (Next)  -> (254, 739)
#   தவிர்க்கவும்  (Skip)  -> (254, 814)
#
# The helpers come from the loop script: click X Y [settle-seconds], type_text, press KEY, shot NAME.
# Every shot lands in the run directory, so this file's job is to leave a photograph at each step the
# flow passes through - including the step that fails, which is the one worth seeing.

shot 01-tour-page-1

# Page 1 -> page 2. A page transition is an animation, so the settle is longer than a tap's.
click 254 739 2
shot 02-tour-page-2

# Page 2 -> page 3.
click 254 739 2
shot 03-tour-page-3

# Page 3 -> whatever the tour hands over to. This is the step the fresh-install flow was failing at,
# so the shot after it is the evidence: either the shared database-init screen, or an error dialog.
click 254 739 4
shot 04-after-tour

# Give the next screen a moment to compose and, if it is a gate, to reach the data layer that the
# failure came from. A longer settle here is deliberate: the crash reported by the app happens during
# composition, so a shot taken too early would show a screen that had not yet failed.
sleep 6
shot 05-settled

# One more frame after a further wait, so a screen that failed late still appears, and so a stable
# screen can be told apart from one that was still painting.
sleep 6
shot 06-final
