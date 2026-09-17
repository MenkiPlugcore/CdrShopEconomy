# Changelog

## 1.0.0-beta.2

- Audit-first stability release before adding finite stock and dynamic pricing.
- Pre-payment inventory persistence failures now attempt a safe rollback before Vault is called.
- A successful pre-payment rollback closes the journal entry as ABORT instead of unnecessarily locking the player.
- If rollback durability cannot be proven, the player remains locked for manual review.
- Once Vault execution begins, exceptions remain fail-closed because the external payment outcome can be ambiguous.
- Journal replay now rejects duplicate BEGIN records and terminal records without a matching BEGIN/player.
- Journal details have a size guard to prevent pathological synchronous disk writes.
- Added regression tests for transient pre-payment save failure and orphan journal terminal records.
- Maven/plugin/build artifact metadata bumped to 1.0.0-beta.2.

Finite stock, restock, categories, dynamic pricing, transaction-history UI and custom-item adapters remain planned after the transaction foundation is verified.

## 1.0.0-beta.1

- Initial Paper 1.21.11 / Java 21 implementation by CADERA.
- `/toko`, `/jualgui`, `/jual`; no `/shop` alias.
- Citizens optional: AUTO, COMMAND, NPC_ONLY and configurable missing-Citizens fallback.
- NPC binding, per-shop permissions, proximity and session revalidation.
- Virtual sale basket with explicit total and confirmation; no pre-sale item custody.
- Paginated shop menus and buy amount/confirmation screens.
- YAML shop catalogs; in-game template import and GUI price editor.
- Exact template matching, cents arithmetic, cross-shop direct-price arbitrage checks.
- Vault provider response validation, inventory snapshot planning, synchronous audit journal and manual unresolved-transaction review.
- Core tests, staging checklist, Maven build and GitHub Actions build artifact.

This release does not include dynamic prices, stock, contracts, reputation, economy events, Bedrock Forms or dedicated custom-item-provider adapters.
