# Manual test checklist

Automated tests cover the logic. This checklist is for the parts only a real client can check:
how it looks, how it sounds, and how it feels with real latency. Nothing here has been done yet.
Tick the boxes as you go and write down anything odd.

**Setup:** see [MULTIPLAYER_TESTING.md](MULTIPLAYER_TESTING.md). You need at least two players
for the squad sections. Both should be op (`op <name>` in the server console) unless a step says otherwise.

**Kit for each tester.** Give each player this with `/give` (replace `<name>`):

```
/give <name> iron_sword
/give <name> iron_boots
/give <name> iron_leggings
/give <name> iron_chestplate
/give <name> iron_helmet
/give <name> shield
```

Keep the sword in the selected hotbar slot. Put the armor and shield in the inventory, not in hand.

Result key: ✅ pass, ❌ fail, ➖ not tried.

---

## 1. Startup and commands

- [ ] Server log shows `SyncEquip` enabled with no errors or stack traces.
- [ ] `/syncequip help` lists all commands.
- [ ] Typing `/syncequip ` and pressing Tab suggests `solo`, `squad`, `settings` (and `reload` for ops).
- [ ] `/syncequip squad ` and Tab suggests create, invite, join, leave, list, start, cancel, transfer.
- [ ] `/syncequip settings` shows interval 2 ticks and the order Boots, Leggings, Chestplate, Helmet, Shield.
- [ ] `/syncequip nonsense` shows an "unknown subcommand" message.

## 2. Solo mode

- [ ] `/syncequip solo` puts on the pieces one after another, not all at once.
- [ ] The order is boots, leggings, chestplate, helmet, shield.
- [ ] The whole thing takes roughly half a second.
- [ ] The sword stays in your hand.
- [ ] An action bar progress bar appears and ends on "Equipped 5/5".
- [ ] Inventory afterwards: the five items are gone from storage and nothing else changed.
- [ ] Running `/syncequip solo` again right away shows a cooldown message.
- [ ] After the cooldown, with armor taken off and given back, it works again.
- [ ] Wearing other boots already: they are swapped for the kit boots and the old ones return to the inventory.
- [ ] Missing the helmet: the command refuses and says "missing Helmet". Nothing was equipped.
- [ ] With the shield missing and `missing-item-policy: SKIP` in config (then `/syncequip reload`),
      the other four pieces go on and the shield step is skipped.
- [ ] Enchanted, renamed and damaged armor keeps its enchantments, name and durability.
- [ ] Mixed set (leather boots, diamond chestplate, netherite helmet and so on) equips fine.
- [ ] A shield with a banner pattern keeps its pattern.

## 3. Sounds (listen with headphones)

- [ ] Each piece makes its own sound, one per step. No step makes two sounds.
- [ ] Iron, diamond, leather and netherite pieces sound different from each other.
- [ ] The shield step has a clearly different closing sound.
- [ ] The rhythm feels like CLANK, CLANK, CLANK, CLANK, CLACK.
- [ ] A second player standing a few blocks away hears it, and it comes from the right direction.
- [ ] A player 40+ blocks away does not hear it.
- [ ] With `sounds.enabled: false` and a reload, vanilla armor sounds play (the shield is silent). No doubled sounds.
- [ ] Try `volume` 0.5 and `pitch` 1.2 and confirm they change what you hear.
- [ ] Note which sounds you would change: ______________________

## 4. Squad management

- [ ] `/syncequip squad create` works, and a second create says you are already in a squad.
- [ ] `/syncequip squad invite <friend>`: the friend gets a message with a clickable join command.
- [ ] Friend runs `/syncequip squad join <leader>` and everyone sees "joined the squad".
- [ ] Joining without an invite is refused.
- [ ] An invite stops working after 60 seconds.
- [ ] `/syncequip squad list` shows both names and marks the leader.
- [ ] A non-leader cannot invite, start or cancel.
- [ ] `/syncequip squad transfer <friend>` makes the friend leader and both see it.
- [ ] `/syncequip squad leave` as a member removes only that member.
- [ ] Leader leaves: leadership passes to the next member.

## 5. Squad sequence and synchronization

Use two or more players standing next to each other, ideally in a line, both with the kit.

- [ ] Leader runs `/syncequip squad start`. Everyone sees the title countdown 3, 2, 1 together.
- [ ] A soft tick sound plays on each countdown beat.
- [ ] After the countdown, every player's boots appear at the same moment, then leggings, and so on.
- [ ] From the leader's screen, the other player's pieces appear in step with their own.
- [ ] From the other player's screen, the leader's pieces appear in step with their own.
- [ ] The sound pattern is the same rhythm for everyone, overlapping.
- [ ] If one player is missing a piece, the leader sees who and what, and nothing starts.
- [ ] Leader runs `/syncequip squad cancel` during the countdown: nothing is equipped and everyone is told.
- [ ] Cancel after pieces started going on: those pieces stay on, the rest stay in the inventory, and the message says how many.
- [ ] A member who disconnects during the countdown does not stop the rest.
- [ ] Starting a second sequence while one is running is refused.
- [ ] Two separate squads (needs four players) can run at the same time.

**Proving same-tick execution.** In `plugins/SyncEquip/config.yml` set `debug.log-sequence-ticks: true`,
run `/syncequip reload`, then run a squad sequence. In the server console or `logs/latest.log`, find lines like:

```
[debug] sequence #1 step 1/5 BOOTS  player=Alice scheduled=... actual=... drift=0 result=EQUIPPED
```

- [ ] For each step, every player has the same `actual` value.
- [ ] The five steps are 2 ticks apart (for example 1000, 1002, 1004, 1006, 1008).
- [ ] `drift=0` on a healthy server.

Note: the log proves the server did it on one tick. It can't show what each client displays.
Different pings can make it look slightly off on some screens, which is expected.

## 6. Hotkey (sneak + swap hands)

- [ ] With the trigger off (default), Shift + F just swaps hands.
- [ ] Set `controls.sneak-swap-trigger: true` and reload. Shift + F now starts a solo sequence.
- [ ] Your offhand item is not swapped when it fires.
- [ ] F without Shift still swaps hands normally.
- [ ] Holding Shift + F or tapping it repeatedly does not start extra sequences.
- [ ] The cooldown message shows if you spam it after finishing.
- [ ] If you rebind the swap key in Controls, the trigger follows the new key.
- [ ] With `controls.sneak-swap-action: AUTO`, the squad leader pressing Shift + F starts the squad sequence.

## 7. Inventory safety

Note what is in your inventory before each test so you can compare afterwards.

- [ ] While equipping, try to click items in your inventory. Clicks are blocked for that short moment.
- [ ] Try to drop an item or press F mid-sequence. It is blocked briefly, then works again after.
- [ ] During a squad countdown, you can still move items freely.
- [ ] During the countdown, remove your helmet from the inventory. At the start you get removed
      with an "inventory unchanged" message, and the others still equip.
- [ ] Die mid-sequence (`/kill <name>` from another op while it runs, or lava). The sequence stops
      for you, nothing is duplicated, and your drops match what you carried.
- [ ] Quit the game mid-sequence and rejoin. Items are where you left them, nothing missing or doubled.
- [ ] Teleport to another world or dimension mid-sequence (`/execute in minecraft:the_nether run tp @s ~ ~ ~`
      from another op). The sequence stops with a clear message.
- [ ] Stop the server (`stop`) during a sequence, then restart. Inventories are intact.
- [ ] Holding an axe with the sword on another hotbar slot: the selected slot switches to the sword at its step.
- [ ] Totem in the offhand: the shield replaces it and the totem returns to the inventory.
- [ ] Throughout all of the above, the total item count never changes.

## 8. Lag and TPS (optional)

- [ ] Run `/tick rate 10` as op to slow the server, then run a squad sequence. It plays slower but all
      players stay together. Run `/tick rate 20` afterwards.
- [ ] Try a friend with high ping. Note what they see compared to what you see: ______________________

## 9. Reload and config

- [ ] Edit `interval-ticks` to 5, run `/syncequip reload`, then a solo run is visibly slower.
- [ ] Set `interval-ticks: 50`. Reload shows a warning and the value is clamped to 10.
- [ ] Reload during a running sequence does not change that sequence.
- [ ] Change the order to `[SHIELD, HELMET, BOOTS]`. Only those three go on, in that order.

---

## Findings

| Area | What happened | Severity | Notes |
|---|---|---|---|
| | | | |
