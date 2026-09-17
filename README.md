# CdrShopEconomy

**MENKIESTES — created by CADERA. Version 1.0.0-beta.1.**

Shop plugin targeting **Paper 1.21.11 / Java 21**. Requires **Vault plus an economy provider** (for example EssentialsX). Citizens is optional. No `/shop` alias is registered.

This is the first core implementation, not the entire Living Economy roadmap. Test on a staging server and back up player data/economy before production use. It does not claim exactly-once transactions across server crashes or third-party economy failures.

## Player flow

| Access | Buy | Sell basket | Sell held stack |
| --- | --- | --- | --- |
| Command | `/toko [shop]` | `/jualgui` | `/jual` |
| NPC | Right-click bound Citizens NPC | Green selling button in that NPC's shop | Select held stack in NPC selling menu |

`/jual` sells the entire **main-hand stack only**, never all inventory stacks. Armor and offhand are excluded. Players must be alive and in Survival or Adventure.

The sell GUI uses a **virtual basket**: click items in the bottom player inventory, inspect total, confirm. It copies selections visually; it never takes custody of the original items before confirmation. Closing, disconnecting or shutting down an idle menu needs no recovery. Drag, shift-click, number keys, double-click and drop actions in the menu are blocked. If any selected stack changes before confirmation, the whole sale is rejected.

## Install

1. Build with Java 21 and Maven 3.9+: `mvn -B clean verify`.
2. Stop the staging server. Put `target/CdrShopEconomy-1.0.0-beta.1.jar` in `plugins/`.
3. Install Vault and a compatible economy provider. Optionally install Citizens compatible with your server.
4. Start the server. Check `/cdrshop status`. Three sample items appear in `shops/umum.yml`.
5. Test the checklist in `TESTING.md`. Do not use PlugMan or Bukkit `/reload`; restart for plugin changes. `/cdrshop reload` only reloads this plugin's configuration.

## Access policy

`config.yml` supports:

- `AUTO` (default): Citizens loaded **and at least one NPC binding configured** => NPC-only transactions globally. With no binding, commands remain usable so installing Citizens for other purposes does not accidentally lock the shop.
- `COMMAND`: only player commands, NPC opening is ignored.
- `NPC_ONLY`: requires bound NPCs; commands are refused, including for OP.
- `missing-citizens: COMMAND`: fall back to commands when Citizens is missing/disabled.
- `missing-citizens: DISABLE`: fail closed if Citizens is unavailable (recommended for strict RP).

If a configured NPC is deleted or despawned while Citizens remains enabled, the plugin **does not** silently unlock commands. Repair the NPC/binding or change the mode explicitly. NPC sessions enforce shop permissions, binding, same world, maximum distance and timeout again when trading. Admin permission does not bypass NPC transaction rules.

Create an NPC using Citizens, find its numeric ID, then bind it:

```text
/cdrshop bind umum 12
/cdrshop unbind 12
```

One NPC points to one shop; multiple NPCs can point to the same shop. NPCs only buy the items listed in their shop. NPC dialogue, schedules and finite stock are future work.

## Admin item editing

```text
/cdrshop create farming
/cdrshop editor
/cdrshop editor farming
/cdrshop add farming wheat 20 8
/cdrshop price farming wheat 25.50 10
/cdrshop remove farming wheat
/cdrshop reload
/cdrshop status
```

`add` copies the held item, including its serialized item data. Existing IDs are rejected rather than overwritten. `remove` deletes the listing only.

GUI: choose a shop, click an inventory item to copy it as a new template, enable and adjust buy/sell prices, then Save. Click an existing listing to edit its prices. Buttons adjust by 1 or 10 currency units; use `price` for exact decimals. Both directions begin disabled for new GUI entries. `-1` disables a buy/sell direction; prices otherwise must be positive with at most two decimals. Create shop names through the command. All GUI/file changes share the same catalog.

Manual file example (`plugins/CdrShopEconomy/shops/farming.yml`):

```yaml
title: '&aPedagang Hasil Panen'
permission: ''
items:
  wheat:
    material: WHEAT
    buy: '20.00'
    sell: '8.00'
```

Imported templates use a Base64 `data` field instead of `material`. Edit their prices directly, but do not hand-edit `data`. Templates are stored at quantity one. Exact item similarity is required for sales: renamed, enchanted, damaged or custom items do not match plain material listings accidentally. This conservatism intentionally rejects changed durability or lore.

Serialization preserves the template data; **it is not an ItemsAdder/MMOItems/ExecutableItems API integration**. Provider-managed unique IDs, randomized items, ownership, cooldowns and state outside the ItemStack need their own adapters. Do not list unique/non-clonable items in this beta. Custom item providers/resources must still be installed for their behavior and visuals.

## Transaction safeguards and limits

- Integer cents internally; checks for invalid/negative prices, arithmetic overflow, inventory capacity, permissions, stale selections and economy responses.
- Fixed unlimited-stock admin shops. Buying requires enough inventory space; nothing drops on the ground.
- Duplicate templates in one shop rejected; identical templates across shops cannot have sell prices above another shop's buy price. Other plugins, crafting conversions and altered item metadata are outside this check.
- Command selling chooses the best available sell price across shops the player may access; NPC selling considers only that NPC's shop.
- All inventory/Vault calls are synchronous. Concurrent/reentrant trades for the same UUID are blocked.
- `transactions.log` is a forced append-only journal with transaction IDs, UUIDs, amounts and Base64 inventory-before/after snapshots. It contains private player inventory data: restrict file access.
- A provider-declined transaction restores inventory. An exception or process crash with an unfinished journal entry **locks that UUID for manual review**. Inventory and external economy storage cannot be atomically committed together. Never blindly refund/replay unknown payments.
- Journal writes and `saveData()` are synchronous for conservative durability, so high transaction rates may affect tick time. Benchmark before production. The log does not rotate automatically in this beta; archive only while stopped, retaining all pending records.
- GUI inventory is usable via ordinary clicks; **Bedrock Forms are not implemented**. Geyser/Bedrock usability requires in-game testing. UUID tracking does not detect linked alt accounts.

Console-only recovery:

```text
cdrshop pending
cdrshop resolve <transaction-UUID> reviewed
```

Inspect journal, current inventory and economy logs first; manually reconcile any incomplete operation. `resolve` only releases the lock, without paying/refunding or changing inventory. A corrupted journal prevents plugin startup; preserve the file and repair it after investigation.

## Permissions

`cdrshopeconomy.use` defaults to everyone. `cdrshopeconomy.admin` defaults to OP. Per-shop `permission` is optional. Financial review commands are server-console-only.

## Roadmap (not implemented)

Dynamic prices and stocks; supply contracts; merchant reputation; regional economy; economic events; dedicated custom-item adapters; native Bedrock Forms; database-backed analytics and history UI; PlaceholderAPI; native economy provider. The beta has no automatic taxes, market health scoring or anti-alt detection.

## References

- [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/)
- [Paper 1.21.11 ItemStack API](https://jd.papermc.io/paper/1.21.11/org/bukkit/inventory/ItemStack.html)
- [Vault API](https://github.com/MilkBowl/VaultAPI)
- [Citizens API](https://jd.citizensnpcs.co/net/citizensnpcs/api/event/NPCClickEvent.html)
