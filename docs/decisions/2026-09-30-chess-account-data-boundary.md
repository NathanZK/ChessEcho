# Chess account data boundary

## Context

Chess.com account identity and imported game data are shared, while puzzle history and training attempts belong to an individual user. A mutable account connection can change independently of imported data and in-flight jobs.

## Choice

- Keep shared imported data on `ChessAccount`; treat its user pointer as the current connection and import-initiation authorization only.
- Scope authenticated personal training rows by both `AppUser` and `ChessAccount`; disconnect preserves these rows.
- Attribute authenticated import jobs and emitted personal events to the initiating `AppUser`. Only that user may read authenticated job status.
- Permit authenticated shared-data reads for any existing account. Guest reads, imports, and job-status access remain limited to unclaimed accounts.
- Use Approach B: direct nullable `app_user_id` relationships rather than a separate connection entity.
- Use `ON DELETE NO ACTION` for the new user foreign keys until account deletion/erasure has an explicit product policy.
- Preserve guest replay protection with a PostgreSQL partial unique index using `NULLS NOT DISTINCT`; the Flyway baseline defines the pre-deployment schema.

## Ruled out

- Using the mutable account connection owner as the personal-history owner or job-status principal.
- Cascading user deletion into training history or audit jobs without an approved deletion policy.
- A connection entity without a concrete lifecycle or metadata requirement.
- Expressing the partial PostgreSQL uniqueness rule as a table-wide JPA unique constraint.
