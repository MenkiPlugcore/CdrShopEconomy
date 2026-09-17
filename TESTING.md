# Staging acceptance checklist — 1.0.0-beta.2

Automated tests cover cents/price validation, access routing, journal durability, payment rejection, unknown outcomes, restart locks, reentrancy and audit hardening. They do not substitute for real Paper/Vault/Citizens/Geyser integration tests.

## Core transaction flow

- [ ] Boot Paper 1.21.11 + Vault + economy, no Citizens. `/toko`, `/jualgui`, `/jual` work; `/shop` is not registered by this plugin.
- [ ] Missing provider: startup fails with actionable log; no financial transaction runs.
- [ ] Buy 1/16/32/64 with sufficient balance. Inventory, balance and finite stock change exactly once.
- [ ] Insufficient balance: player inventory and finite stock are both restored.
- [ ] Full inventory: transaction is refused before payment and stock remains unchanged.
- [ ] `/jual` with two inventory stacks sells only the main-hand stack. Offhand/armor untouched.
- [ ] GUI preview matches payout. Select/deselect/all works; unsellable items stay untouched.
- [ ] Close GUI, disconnect, die, teleport or restart before confirmation: original items remain.
- [ ] Shift-click, double-click, drag, hotbar keys, swap-offhand and drop never extract menu icons or duplicate originals.
- [ ] Change a selected stack through another plugin: confirmation refuses stale selection.

## Finite stock and restock

- [ ] New beta.2 sample shop creates finite wheat/iron state in `stock.yml`; stone remains unlimited.
- [ ] Restart preserves current stock instead of resetting to `initial`.
- [ ] `/cdrshop reload` preserves runtime stock while reconciling removed/new products.
- [ ] Buying finite stock decrements exactly by purchased quantity.
- [ ] Selling a finite-stock item replenishes its stock up to `stock.max`; excess never raises state above max.
- [ ] Stock 0 still displays the listing but purchase selection is refused.
- [ ] `/cdrshop restock` adds each product's configured `stock.restock` and never exceeds max.
- [ ] Auto-restock runs at `restock-interval-seconds`; setting 0 disables it.
- [ ] `/cdrshop stock <shop> <id> -1 -1 0` converts the listing to unlimited stock and disables dynamic pricing.
- [ ] Invalid max/initial/restock values fail without corrupting the last valid shop file/state.

## Dynamic pricing

- [ ] `dynamic-percent: 0` behaves exactly like fixed beta.1 pricing.
- [ ] At roughly half stock, dynamic quote is approximately the configured base price.
- [ ] Low stock raises both buy/sell quotes; high stock lowers them while preserving the spread.
- [ ] Quote shown in the confirmation GUI equals the amount actually charged/paid.
- [ ] Change stock between amount selection and confirmation: current quote is refreshed or stale confirmation is refused.
- [ ] Two shops listing the same exact template cannot expose an immediate buy-low/sell-high loop at their current stock levels.
- [ ] Dynamic pricing on unlimited stock is rejected by configuration validation.

## Categories and administration

- [ ] One accessible category: `/toko` opens shops directly.
- [ ] Multiple accessible categories: `/toko` opens category selection, then only shops in that category.
- [ ] Per-shop permission hides inaccessible shops/categories correctly.
- [ ] `/cdrshop category <shop> <category>` persists after restart.
- [ ] GUI editor copies item, Save persists, Cancel does not persist. Existing stock/dynamic metadata survives a price edit.
- [ ] `/cdrshop history`, player filtering and limit 1–50 return recent compact records.
- [ ] `history.log` failure only logs a warning and does not reverse an already-completed financial transaction.

## Citizens / RP access

- [ ] Citizens installed without bindings: AUTO still permits command access.
- [ ] Bind one NPC: AUTO blocks `/toko`, `/jualgui`, `/jual` including for OP. NPC opens matching shop.
- [ ] During an NPC session, leaving radius/world prevents trading.
- [ ] Wrong NPC shop cannot accept another shop's items. Permission denial is enforced.
- [ ] Delete/despawn bound NPC at runtime: command access stays blocked.
- [ ] A stale `npcs.yml` binding to a missing shop is rejected at plugin load/reload with an actionable error.
- [ ] Missing Citizens uses configured fallback; strict DISABLE blocks access.

## Item identity / compatibility

- [ ] Custom lore/PDC/component item is preserved; vanilla counterpart cannot sell as custom and vice versa.
- [ ] Enchanted/damaged/renamed/container-with-contents items cannot sell as a plain template.
- [ ] Test Java client and Geyser/Floodgate Bedrock clicks independently.
- [ ] ItemsAdder/MMOItems visuals and behavior remain provider-dependent; unique/non-clonable items are not used until dedicated adapters exist.

## Recovery / failure injection — staging only

- [ ] Clean Vault decline reports zero moved amount: inventory/stock restore and journal writes ABORT.
- [ ] Simulated pre-payment persistence failure that can recover: original state restores and no pending lock remains.
- [ ] Simulated unrecoverable persistence failure: player remains pending for manual review.
- [ ] Simulated Vault exception after possible payment: UUID remains locked; inventory/stock after-state and BEGIN survive restart.
- [ ] Simulated Vault failed response with non-zero moved amount: treated as ambiguous, never auto-restored.
- [ ] Simulated Vault successful response with a mismatched amount: treated as ambiguous.
- [ ] Orphan/mismatched/corrupt journal row prevents unsafe startup.
- [ ] `cdrshop resolve <tx> reviewed` only releases the lock after manual reconciliation; it never pays/refunds or changes items/stock.

## Performance

- [ ] Load test multiple simultaneous users and measure tick time/disk latency.
- [ ] Watch `transactions.log` growth because full inventory snapshots are intentionally durable and synchronous.
- [ ] Watch `history.log` growth; beta.2 does not rotate it automatically.

Do not deliberately crash or inject economy failures on a production server. Keep backups of player data, the economy database, `stock.yml` and transaction journal for coordinated restore.
