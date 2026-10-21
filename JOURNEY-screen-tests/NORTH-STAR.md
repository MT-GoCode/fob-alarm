# North star

**One sentence:** the screens are tested by looking at them, so a role defect cannot ship again.

## Why this exists

Four defects shipped in one blind spot, all in role-conditional UI, none reachable by the 135-test
suite or the 46 mutants:

| version | branch | what the sentinel got |
| --- | --- | --- |
| 0.3 | `isAlarmRole && s == null` | fell through to `s!!` -- crash on launch |
| 0.3.116 | `trying = if (chosen == CONTROLLER)` | no connect screen, so no way to enter credentials |
| 0.3.117 | `if (role == CONTROLLER)` in `Pairing` | the host's editor, locked behind the alarm's password |
| 0.3.118 | `if (iAmAlarm) s.peer else s.self` | its own battery labelled "Controller" |

Each was found by the owner using the phone, after I had read the code and declared it correct.
Reading is the assurance that failed four times. `:service` has never had one of these, because it
routes on `when (role)` with all three arms and the compiler enforces it.

## SHINY -- what makes this worth shipping

1. **Each of the four defects has a test that goes red when the fix is reverted.** Not a test of
   the same area: the actual defect, at screen level, proven by putting the bug back.
2. **One command runs it.** `./gradlew test` grows to include the screens; there is no second suite
   and no device.
3. **A contact sheet of every role in every state**, rendered off-device, that a human can look at
   in one glance and see that a sentinel is never offered a dismiss button.
4. **Every role-conditional UI decision is one exhaustive value in `:core`**, so a fifth role would
   be a compile error in every place that has to handle it, and the mutation harness can attack it.

## Non-negotiable

- Tests assert what is on the screen, never that a function was called.
- Every new test names the mutant that reddens it. A test no mutant can redden is deleted.
- The four regression tests are proven by reverting the real fix, not by a stand-in.
- No second test entry point. No emulator. No device.
