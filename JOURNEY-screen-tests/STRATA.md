# Strata

Rewritten after adversarial review found the first version unbuildable. Every stratum leaves
`./gradlew test` green and is reached by that one command. Each names the mutant that proves its
tests are not decoration.

**Order principle, from the review:** characterise what is reachable BEFORE refactoring, so the
refactor's green signal is "the existing screen tests still pass" rather than "it compiles". Only
the decisions held in `:app` need the refactor first, and they are deliberately last.

## STRATUM 1: the harness, with the two seams that make absence assertions mean anything

**Shape.**
- `Fixtures.kt` (written): `FakeHost` behind the REAL `LocalStateClient`, a fully defaulted
  `snapshot(role, ...)` builder, `gatesAllPass()`. `evaluatedAtMs` defaults non-zero, or the Setup
  tab is a spinner and every Setup assertion is vacuous.
- **An injected clock in `AppState`.** `nowMs` currently only advances inside `startPolling`, whose
  loops must never run under the compose clock -- so "a joiner, disconnected, holding a stale
  snapshot" is unbuildable without waiting 30 s, and that is stratum 4's main cell.
- **One `renderRoot(role, state, slots)` helper, the single entry to every screen test.** When
  stratum 4 changes `RootScreen`'s signature it edits this helper, not N tests. Without it, the
  later strata are surgery on the earlier ones.
- The snapshot is installed synchronously BEFORE `setContent`, or the tree is a spinner and every
  "no Dismiss button" check passes for the wrong reason.
- `FakeHost` answers reads and RECORDS every call that changes the alarm; an `@After` asserts the
  record is empty. Not a throwing fake: `LocalStateClient` wraps host calls in `runCatching`, so a
  throw is swallowed into a snackbar, a retry, or nothing, and fails no test. This line has been
  wrong twice; it now says what the code does.

**Verifiable execution.** `./gradlew test` runs `:ui:testDebugUnitTest` beside `:core` and `:data`.
**Human-verifiable result.** `renderRoot(SENTINEL)` produces a tree whose dumped node list is
printed in the test output.
**Mutant.** `snapshot()` returning a snapshot with no `nextFire` -- the positive anchor assertions
across stratum 2 redden, proving the fixture is load-bearing rather than the screens being blank.

**RULE for every later stratum:** each `assertDoesNotExist` is paired, in the same test, with an
`assertExists` on a positive anchor unique to the intended screen.

## STRATUM 2: characterise what `:ui` can already reach

**Shape.** Tests of today's behaviour, no production change:
- defect 4: a sentinel's power rows never label it "Controller", and DO show "This phone".
  **Revert-proven here** -- this is the one of the four that is genuinely revert-provable.
- sentinel authority: no Dismiss, Skip, Move or Nap node in any state, including while ringing.
- tab sets per role: SENTINEL is exactly [Status, Setup]; others have Settings and History.
- ring takeover: a ring does not take over a sentinel's screen; it does for the other two.
- `LinkBar` wording per role.
- Each test clicks through every tab in that role's list, or only tab 0 is ever observed.

**Verifiable execution.** Suite green; defect 4's fix reverted by hand, its test red, restored.
**Human-verifiable result.** A named test per owner-visible rule, in owner language.
**Mutants.** Defect 4's revert, plus one flipping the sentinel's tab list.

## STRATUM 3: `RoleUi` in `:core`, and all `:ui`-internal routing

**Shape.** One exhaustive `when (role)` producing sum-typed fields, NOT a bag of booleans -- the
review's finding: `hostsGroup` and `showsConnectScreen` were always each other's negation, and
authority was encoded three times.

```
data class RoleUi(val link: Link, val ring: RingFace, val tabs: List<Tab>, val acts: Boolean)
enum Link      { HOSTS, JOINS }                 // host editor vs joiner summary, connect screen,
                                                // LinkBar wording, dimming
enum RingFace  { BOX_TRACK, BUTTON, NONE }      // NONE is the sentinel. A boolean could not say
                                                // WHICH face, so isAlarmRole survived inside it
enum Tab       { STATUS, SETUP, SETTINGS, HISTORY }   // route on the value, never the index
```

Authority is derived ONCE inside `RoleUi.of`. Every `isAlarmRole: Boolean` parameter in `:ui` is
deleted as a type, so the compiler is the gate rather than a grep count.

**Verifiable execution.** Stratum 2's tests pass UNCHANGED. That is the whole proof.
**Human-verifiable result.** No `isAlarmRole` parameter survives in `:ui`.
**Mutants.** Each `RoleUi` field flipped for the sentinel must redden a stratum-2 TREE test. Note
the review's point: per-field `:core` unit tests asserting `RoleUi.of(SENTINEL).acts == false`
restate the table and are forbidden. The expectation lives in one hand-written table, in owner
language, or in the tree.

## STRATUM 4: move the `:app`-held decisions, and test what just became reachable

**Shape.** The decisions that no `:ui` test can see today, because `:app` decides by whether it
passes a slot at all:
- every slot non-nullable and passed unconditionally (`trying`, `deviceSettings`);
- `Pairing(role)` split into two role-free slots, `hostPairing` and `joinerSummary`, so the choice
  is made in `:ui` from `RoleUi.link`;
- `screenFor(role, snapshot, connected, nowMs): Screen` -- sealed `Starting | Connecting(stale:
  Snapshot?) | Ringing(s, face) | Tabs(s, tabs)` -- so the snapshot exists only in the variants that
  have one and defect 1's class becomes unrepresentable rather than merely tested.

**Verifiable execution.** Tests pass marker composables (`Text("HOST-EDITOR")`) and assert which
marker appears per role -- not tautological, because `:ui` makes the choice.
**Human-verifiable result.** Defects 1, 2 and 3 become reachable; 1 and 2 collapse into one
observable, "a non-hosting phone with no snapshot shows the connect screen".
**Mutants.** One per slot decision and per `Screen` variant.
**Residue, to be recorded in DEBT, not claimed as covered:** `:app` passing the right composable
into the right named slot, and the content of the slots themselves.

## STRATUM 5: the state sweep

**Shape.** Table-driven over role x (no snapshot, disconnected with stale, connected, ringing, test
ring, nap armed, override set, blocked ring, setup incomplete). Each cell renders, and the
hand-written expectation for that cell holds -- never an expectation read from `RoleUi`, which would
move with any mutant and assert nothing.

## STRATUM 6: the contact sheet, as an artifact and not a gate

**Shape.** Roborazzi capture inside stratum 5's sweep: no second test file, no committed goldens, no
compare mode, so no pixel-diff maintenance. One PNG per cell plus an index, published to the
artifact host as one URL.
**Unverified until probed, so this stratum may not be built on:** Robolectric native graphics
(`@GraphicsMode(NATIVE)`) at sdk 34 on this machine, and whether Roborazzi writes anything without
a record property. If it needs one, it goes in gradle config so plain `./gradlew test` writes it.

## STRATUM 7: `GateInfo.forRole` -- DONE EARLY, out of order

Landed before this rewrite because it was not a test gap but a live defect: a sentinel reported the
controller's permission set, so a phone that never rings had to grant a lock-screen permission or
the gate vetoed its away block for good. Exhaustive `forRole(role: Role)`, `PeerLink` reports its
own role. Tests assert the exact set per role from a hand-written list.

## STRATUM 8: End of the screen-testing period -- adversarial review and ship

Whole-codebase review against `code-principles.md`, hunting vacuous assertions, tests that would
pass with the screens blank, and any role branch left behind. Full suite plus the whole mutation
harness green in one run. Build, serve, install.

## STRATUM 6 — DONE. The contact sheet

Built, and it earned its place in the first image it produced: the sentinel's Status screen printed
a "Link" heading with nothing underneath it, which eighteen assertions, 63 mutants and four rounds
of adversarial review had all missed. Every one of them only checks what someone thought to check.

- `ContactSheetTest` is parameterised over role x state and writes one PNG per cell with Roborazzi,
  under Robolectric native graphics at sdk 34, 411x891 xhdpi. ~4 s a screen.
- Recording is switched on in gradle config, so plain `./gradlew test` writes the images and there
  is no second way to invoke the suite.
- NOT a gate, deliberately: no baseline images, no pixel comparison, nothing to approve when a font
  renders differently. It fails a build only if a screen throws while rendering.
- `tools/contact-sheet` arranges them into one page; it is published as a single URL.
