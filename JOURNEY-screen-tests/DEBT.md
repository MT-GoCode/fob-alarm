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
