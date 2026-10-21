# Decisions

- **Robolectric 4.16 + `androidx.compose.ui:ui-test-junit4`, `@Config(sdk = [34])`.** Probed before
  planning: renders real Compose trees in `:ui` on the JVM in 15-35 s, and a deliberately wrong
  assertion fails with `AssertionError`, so the assertions observe the tree. sdk 34 rather than the
  project's compileSdk 36 because nothing on these screens depends on SDK 36 behaviour and pinning
  low removes Robolectric's SDK-support risk entirely.
- **`:ui` is the test surface.** It depends only on `:data`, never on `:service`, so its screens
  take an `AppState` and a `Snapshot` and need no Android service running. Three of the four
  defects lived there.
- **Semantics assertions catch defects; screenshots are for humans.** Both, in that order of
  priority -- a pixel diff tells you something changed, not what is wrong.
- **Characterise before refactoring.** Refactoring first would leave "it compiles" as the only
  green signal for that stratum, which breaks inductiveness. Only the decisions held in `:app` --
  where no `:ui` test can observe them -- need the refactor first, and they are last.
- **Moving a decision into a pure value is not sufficient.** If `:app` still decides whether to
  pass a slot, a `:ui` test that passes the slot itself is tautological however pure the value is.
  The decision must move into the module under test.
- **Of the original four defects, only two were ever testable as stated.** #1 and #2 collapse into
  one observable ("a non-hosting phone with no snapshot shows the connect screen"); #3 is latent --
  `SetupScreen` calls `pairing` only `if (isAlarmRole)` and the sentinel has no Settings tab, so
  `Pairing`'s sentinel arm is dead code on HEAD and reverting it changes nothing on any screen.
  A side effect worth fixing separately: a connected sentinel has no screen showing which group it
  joined.
