# Debt

- **`GateInfo.blocking` does two jobs.** It means "setup will not work without this" AND "report
  this to the peer, which vetoes the away block". That is why `notHibernating` -- a real
  polling-throttle risk, and exactly the kind of thing that should make a sentinel's silence
  untrustworthy -- does NOT veto, while `batteryUnrestricted` does, purely because 0.3 made the
  former non-blocking for setup reasons (OEM-buried, no reliable deep link). One field, two
  audiences, and the split between them is now accidental rather than decided. Needs an owner
  decision before it is changed, so it is recorded rather than fixed.
- **`PeerLink.missingFor(role)` is untestable.** `:service` has no unit tests, so the fix that
  actually stops a sentinel reporting the controller's set is proved only by the `:core` behaviour
  it calls. The call site itself is unguarded.

- **`RoleUi` in `:core` -- the structural fix, deliberately not done.** `LinkBar`,
  `RingingScreen` and `SettingsScreen` still take `isAlarmRole: Boolean`, which is the
  two-way-branch-over-three-roles shape behind all five role defects. The case for doing it: a
  fifth role, or a new screen, would inherit the `else` again. The case against doing it NOW, which
  won: all three sites are correct today, two are unreachable for a sentinel, the class is covered
  by screen tests and mutants over those screens, and it is 200-300 lines of no-behaviour-change
  refactor across five files on the screens the fire path uses -- this project's record is that six
  of round 32's fourteen defects were introduced while fixing something else. Do it as its own
  round, with the suite green before and after and nothing else in the diff.
  The shape, already designed: `RoleUi.of(role)` returning sum-typed fields --
  `Link { HOSTS, JOINS }`, `RingFace { BOX_TRACK, BUTTON, NONE }`, `Tab` routed on the value rather
  than an index -- with authority derived once inside it. Not a record of booleans: `hostsGroup`
  and `showsConnectScreen` would always be each other's negation, and a boolean cannot say WHICH
  ring face, so `isAlarmRole` would survive inside it.
- **The gate's reasons are log tokens shown verbatim to the owner.** On the alarm phone's Status
  screen, "Sentinel away 20m" sits directly above "Next ring blocked: sentinel away 1200s" -- the
  same duration, twice, in different units, because `RingVerdict.Blocked.reason` is a sentence
  built for history and then printed. Same root as the raw `Vetoed` tokens in the away rows
  (`power_save`, `low_battery:14`). The fix is NOT to reformat the string: parsing "1200s" back out
  would be the exhaust-based derivation this codebase keeps deleting. It is for the verdict to
  carry structure -- role and seconds -- and for the sentence to be built once at each audience,
  history and screen. Found by looking at the contact sheet.
