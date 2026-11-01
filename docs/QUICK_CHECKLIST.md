# Quick gameplay checklist

One page for testers. The full list is in [TESTING.md](TESTING.md). Mark each line ✅, ❌ or ➖ (not tried).
Note anything odd next to it, with the time, so it is easy to find in the logs.

**Before you start:** you are whitelisted and op, you ran `/function syncequip_test:kit`, and your
inventory shows a sword in slot 1, netherite boots, leggings, chestplate and helmet and a shield in
the top inventory row, and nothing worn. Keep slot 1 selected.

## Demo mode (one person, ops only)

- [ ] `/syncequip demo spawn 10` puts ten husks in a row in front of you, facing you, with no armor
- [ ] `/syncequip demo start` counts down 3, 2, 1 and each piece then appears on every husk at the same moment
- [ ] The order is boots, leggings, chestplate, helmet, shield, about 4 ticks (a fifth of a second) apart
- [ ] You hear the armor sounds from the husks, not doubled
- [ ] `/syncequip demo reset` strips them, and `/syncequip demo start` plays it again
- [ ] `/syncequip demo speed 2` makes the next run faster
- [ ] `/syncequip demo repeat 3` runs three times with a short pause
- [ ] `/syncequip demo clear` removes them all, and nothing is dropped

## Solo

- [ ] `/syncequip solo` puts the pieces on one at a time, in the order boots, leggings, chestplate, helmet, shield
- [ ] It takes roughly half a second, not instant
- [ ] The sword stays in your hand
- [ ] After it ends, you wear all four pieces plus the shield and nothing else changed
- [ ] Running it again straight away gives a cooldown message
- [ ] Run `/function syncequip_test:kit` again and it works again

## Squad (2 or more players)

- [ ] Everyone has the kit. Stand in a row, close together, all facing the same way
- [ ] Leader: `/syncequip squad create`, then `squad invite <name>` for each player
- [ ] Others: `/syncequip squad join <leader>`
- [ ] Leader: `/syncequip squad start`. Everyone sees 3, 2, 1
- [ ] All boots appear together, then all leggings, and so on. Does it look in step on your screen?
- [ ] Note who looks late or early and their ping: ______________________
- [ ] Cancel test: start again after `/function syncequip_test:kit_all`, then `/syncequip squad cancel` during the countdown. Nothing should be worn

## Timing

- [ ] After a squad run, check the debug log (see below). Every step shows one tick for all players
- [ ] The five steps are 2 ticks apart, `drift=0`

## Sounds (headphones help)

- [ ] One clank per piece, no doubled sounds
- [ ] The shield sound is different and ends the pattern
- [ ] Other players nearby hear yours, from your direction
- [ ] Anything too loud, too quiet or wrong: ______________________

## Inventory safety

- [ ] Count your items before and after. Nothing lost, nothing doubled
- [ ] Mid-sequence, clicking in the inventory is blocked for a moment
- [ ] During a squad countdown, take your helmet out of the inventory. You get dropped from the sequence with an "inventory unchanged" message and others still equip
- [ ] Quit and rejoin partway through. Items are where you left them
- [ ] Die partway through (another op runs `/kill <you>`). No duplicate items

## Checking the sync log

On the host PC after a squad run:

```powershell
powershell -File D:\syncequip\scripts\check-sync-log.ps1
```

It prints each step with the tick all players got it on, and says whether they matched. Add
`-Out report.txt` to save it. This shows what the **server** did. It cannot show what each screen displayed.
