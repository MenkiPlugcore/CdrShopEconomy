# Changelog

## 1.0.0-beta.2

### Audit hardening
- Pre-payment preparation failures now attempt a safe state rollback before Vault is ever called.
- Vault responses are checked for the reported transferred amount. Partial failures or mismatched successful amounts are treated as ambiguous and remain locked for manual review.
- Journal startup parsing now rejects orphan terminal records, mismatched player UUIDs, duplicate pending transactions and malformed rows.
- NPC bindings are validated against existing shop files at load so stale bindings cannot silently lock AUTO mode onto a missing shop.
- `/cdrshop status`, plugin metadata, Maven version and CI artifact are updated to beta.2.

### Living Economy
- Optional finite stock per product with durable runtime state in `stock.yml`.
- Stock mutation participates in the same guarded transaction path as player inventory.
- Player sales replenish finite stock up to its configured maximum.
- Automatic restock with a configurable interval plus `/cdrshop restock` for an admin-triggered cycle.
- Optional stock-aware dynamic pricing from 0–90% around the configured base price.
- Dynamic quotes include a cross-shop guard so similar items do not expose an immediate buy-low/sell-high loop at the current stock state.

### UX / administration
- Shop-level categories. `/toko` shows a category menu when more than one accessible category exists.
- Compact `history.log` plus `/cdrshop history [player|all] [limit]`.
- `/cdrshop stock <shop> <id> <max|-1> <initial|-1> <restock>`.
- `/cdrshop dynamic <shop> <id> <0-90>`.
- `/cdrshop category <shop> <category>`.
- Editor lore now exposes finite-stock and dynamic-pricing state.

### Compatibility notes
- Existing beta.1 shop files stay fixed-price and unlimited-stock unless beta.2 stock metadata is added.
- Native Bedrock Forms and dedicated ItemsAdder/MMOItems adapters are still not implemented.
- Live Paper/Vault/Citizens/Geyser testing is still required before production deployment.

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
