# Tasks

## 1. Deadline evidence and state transition

- [ ] 1.1 Persist the zone and supported deadline evidence for goal, membership, estimates and play, with migration coverage for legacy active collections.
- [ ] 1.2 Derive estimated met, estimated not met, and undetermined from cutoff evidence. Verify date and zone boundaries, incomplete history, empty membership (including legacy/restored collections), all-unknown and mixed known/unknown estimates, and post-cutoff changes.
- [ ] 1.3 Persist one immutable archive atomically with removal from active queries. Verify stale preview, concurrent edits, repeat finalization, account reset and frozen outcomes.

## 2. Archive experience and backup

- [ ] 2.1 Add the final preview, confirmation and read-only archive. Verify cancel, expiry, active Home and Library exclusions, and accessibility of outcome details.
- [ ] 2.2 Add backup export and validated restoration with released-schema compatibility. Verify unchanged old backups, repeat restore, independent collection ID collisions, stale active copies and invalid input rollback.
- [ ] 2.3 Expose archived outcomes through a read-only domain projection and verify future consumers can access frozen goal and member metrics without live game rows.

## 3. Integration

- [ ] 3.1 Exercise archive to backup and restored archive on a targeted device or emulator. Verify active collections and unrelated backups still work and refresh affected baselines.
