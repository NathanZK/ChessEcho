# Remove Unused Puzzle Events

context:
- `PRESENTED`, `STARTED`, and `SKIPPED` do not provide retained training outcomes.
- The project has no deployed environment and uses the V1 baseline for pre-deployment schema changes.

choice:
- Remove the three event values from the persisted enum and V1 constraint.
- Clear and recreate initialized local databases before running the changed application.
- Keep database cleanup out of application startup.

ruled-out:
- Retaining retired enum values solely for old-row deserialization, because the user approved clearing pre-deployment data.
- Adding a V2 cleanup migration, because the repository's baseline-first convention requires updating V1 before deployment.
- Deleting old rows automatically, because that would make application startup destructive.
