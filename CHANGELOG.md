# Changelog

## 1.1.2 switching instead of refusing

- Occupied slots no longer stop a sequence. A sword on the hotbar is selected at its step, a sword in the inventory swaps
  into the held slot, and worn armor or the offhand item is swapped out and returned to the inventory. Nothing is dropped.
- A squad start no longer fails because one member cannot take part. Members who are missing an item or have no room are
  left out with a reason, and the rest equip together. If nobody can take part, the start is refused as before.
- `equipment.require-empty-slots` is retired. If it is still `true` in your config, the log warns that it is ignored.

## 1.1.1 joining blocks by material

- **Joining blocks are now a block material, not a coordinate.** `/se squad block set obsidian` (or `set` alone to use the
  block under your feet) makes every block of that material, anywhere in the squad's world, add the players who step on it.
  Commands: `block set|remove|info|enable|disable`, with an optional squad name for admins. `pad` was replaced.
- One material per squad per world. `blocks.all-worlds: true` makes a material work in every world.
- Old coordinate pads found in `squads.yml` are retired. They never trigger, the log says so, and the leader is told once.
- Config: `pads:` became `blocks:` (the old keys are still read if `blocks:` is missing).

## 1.1

New

- **Named squads.** `/se squad create [name]`, `squad join <squad or leader>`, `squad info`, `squad disband`.
- **Joining blocks.** First released as one registered block, replaced in 1.1.1 by block materials (see above).
- **Group unequip.** `/se squad unequip` (and `/se unequip` for yourself) takes gear off in reverse order on shared
  ticks, with a room check first, nothing dropped, and members who lack space left out with a reason.
- **Per-squad speed.** `/se squad speed <1-10|default>`, used for both equipping and unequipping.
- **Custom loadouts.** `/se squad loadout show|reset|set|optional|order` with main hand (any sword, any axe or a
  specific item), offhand (shield, totem, ender pearl, any item), and four armor slots (any, a material or an exact
  item). Slots can be disabled or optional.
- **Saved squads.** `squads.yml` keeps name, owner id, speed, loadout and joining block across restarts.
- Config: a `loadout`, `pads` and `synchronization.unequip-countdown-seconds` section, and `sounds.mainhand` and
  `sounds.unequip-pitch-multiplier`. New permission `syncequip.squad.manage`.
- Bot checks: `bot-test/v11-test.js` (bots walk onto the block, run equip, unequip and a custom loadout, then survive a
  restart).

Changed

- **Help menu.** `/syncequip help [page|name]` is now a paged guide with clickable Previous and Next, one topic per
  page (basics, squads, sequences, joining blocks, loadouts, plus demo and admin pages for those allowed to use them). It
  lists every v1.1 command with its real syntax, and a test checks the help against tab completion.
- **Squads now need a sword by default.** The default loadout is sword, shield and four armor pieces. A held sword
  needs no move. To keep the old behaviour set `loadout.default.mainhand: none` in config.yml.
- A squad's sequence has up to six steps (the sword is last), so the default squad run lasts 10 ticks at speed 2.
- Armor already worn and accepted by the loadout counts as done for squads (solo keeps refusing occupied slots).
- `equipment.order` also accepts `MAIN_HAND` and `OFFHAND`.

Fixed

- Messages missing from an older `messages.yml` fell back to a "Missing message" text instead of the bundled default.

## 1.0

Solo and squad sequences, shared tick scheduler, sounds, hotkey, demo mode.
