# Staging acceptance checklist

Automated tests cover cents/price validation, access routing, journal durability, payment rejection, unknown outcomes, restart locks and reentrancy. They do not substitute for real Paper/Vault/Citizens integration tests.

- [ ] Boot Paper 1.21.11 + Vault + economy, no Citizens. `/toko`, `/jualgui`, `/jual` work; `/shop` is not registered by this plugin.
- [ ] Missing provider: startup fails with actionable log; no financial transaction runs.
- [ ] Buy 1/16/32/64 with sufficient balance. Inventory and balance change exactly once.
- [ ] Insufficient balance and full inventory: no loss, no free items.
- [ ] `/jual` with two inventory stacks sells only the main-hand stack. Offhand/armor untouched.
- [ ] GUI preview matches payout. Select/deselect/all works; unsellable items stay untouched.
- [ ] Close GUI, disconnect, die, teleport or restart before confirmation: original items remain. During NPC session, leaving radius/world prevents trading.
- [ ] Shift-click, double-click, drag, hotbar keys, swap-offhand and drop never extract menu icons or duplicate originals.
- [ ] Change a selected stack through another plugin: confirmation refuses stale selection.
- [ ] Citizens installed without bindings: AUTO still permits command access.
- [ ] Bind one NPC: AUTO blocks `/toko`, `/jualgui`, `/jual` including namespaced forms and for OP. NPC opens matching shop.
- [ ] Wrong NPC shop cannot accept another shop's items. Permission denial is enforced.
- [ ] Delete/despawn bound NPC: command access stays blocked. Missing Citizens uses configured fallback; strict DISABLE blocks access.
- [ ] Restart retains item templates, prices, bindings and transaction journal.
- [ ] GUI editor copies item, Save persists, Cancel does not persist. Two disabled prices stay disabled.
- [ ] Custom lore/PDC/component item is preserved; vanilla counterpart cannot sell as custom and vice versa.
- [ ] Enchanted/damaged/renamed/container-with-contents items cannot sell as plain template.
- [ ] Corrupt YAML/reload: previous valid catalog remains; no partially applied prices.
- [ ] Economy returns failed transaction: inventory restores. Exception: UUID locked, pending entry survives restart. Review on staging only.
- [ ] Test Java client and Geyser/Floodgate Bedrock clicks independently.
- [ ] Load test multiple simultaneous users and measure tick time/disk latency.

Do not deliberately crash or inject economy failures on a production server. Keep backups of both player data and the economy database for coordinated restore.
