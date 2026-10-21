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
- **Of the original four defects, two were testable as stated; the other two needed the code to
  change first.** #1 and #2 collapse into one observable, and the nullable slot that caused them no
  longer exists, so there is nothing left to revert -- the class is unrepresentable rather than
  merely tested. #3 WAS latent (`SetupScreen` called `pairing` only `if (isAlarmRole)` and the
  sentinel has no Settings tab, so `Pairing`'s sentinel arm was dead code and reverting it changed
  nothing on any screen); the slot split made the choice `:ui`'s, and
  `sentinel-shown-the-host-editor` now reverts the real defect and reddens the test named after it.
  The product gap the same code hid -- a sentinel joined a group it could never be told the name of
  -- is fixed.
- **Group credentials live on Setup, for every role, in one place.** Before the split the alarm
  phone saw its pairing editor on BOTH Setup and Settings, and a joiner saw its summary only if the
  Settings tab wrapped it around the role switcher -- which the sentinel has no Settings tab to
  reach. One call site in `SetupScreen`, chosen by an exhaustive `when (role)`, replaces three
  conditional ones. User-visible consequence, stated rather than hidden: the alarm phone's
  credential editor is no longer duplicated on the Settings tab.
