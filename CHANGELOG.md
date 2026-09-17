# Changelog

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
