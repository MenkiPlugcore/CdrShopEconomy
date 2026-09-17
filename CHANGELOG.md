# Changelog

## 1.0.0-beta.2

- Hardened transaction rollback before Vault payment begins: inventory/stock state is restored when persistence fails before payment execution.
- Journal replay now rejects duplicate BEGIN and orphan/mismatched terminal records and limits oversized detail payloads.
- Added transaction-safe finite stock engine backed by durable `stock.yml` runtime state.
- Products remain unlimited by default for backward compatibility; finite stock is enabled per listing with max/initial values.
- BUY decrements merchant stock; SELL increases merchant stock up to capacity.
- Global selling can fall back to another eligible shop when a higher-paying merchant has insufficient stock capacity.
- Shop/editor GUI displays live stock and blocks unavailable purchase quantities.
- Added `/cdrshop stock <shop> <id> [amount]` and `/cdrshop stocklimit <shop> <id> unlimited|<max> [initial]`.
- Reload/restart preserves runtime stock and fails closed on corrupt or out-of-range stock data.
- Expanded regression coverage to 46 passing tests, including durable stock restart, buy commit, declined-payment rollback, sell capacity and malformed stock state.

Still not included: automatic timed restock, dynamic prices, contracts, reputation, economy events, Bedrock Forms or dedicated custom-item-provider adapters.

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
