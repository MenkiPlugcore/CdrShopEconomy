# Verification status — 1.0.0-beta.1

- Compiled against actual Paper API `1.21.11-R0.1-SNAPSHOT` with Java 21.
- Maven `verify`: successful.
- Core automated tests: **35 executed, 0 failures, 0 errors, 0 skipped**.
- Covers money validation, access modes/fallback, transaction success/rejection, reentrancy, unknown-payment lock, inventory persistence failure, restart recovery and corrupt-journal refusal.
- Does **not** include live Paper server startup, real Vault provider payments, Citizens click handling, Java/Bedrock client GUI interaction or custom-provider compatibility testing.
- This is a staging beta, not a production certification. Follow `TESTING.md` before production installation.

Known design limits: unlimited stock and fixed prices; GUI virtual selection instead of physical deposits; synchronous journal and player-data saves; manual review for ambiguous economy outcomes; no native Bedrock Forms or custom-item-provider adapters yet.
