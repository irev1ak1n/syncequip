# SyncEquip

**Synchronized, rhythmic armor equipping for Paper servers.**

SyncEquip turns putting on armor into a coordinated five-beat sequence. Instead of everything
appearing at once, each piece goes on separately — boots, leggings, chestplate, helmet, shield —
two server ticks apart, each with its own equip sound:

```
CLANK — CLANK — CLANK — CLANK — CLACK
```

Its main feature is **squads**: any number of players can run the same sequence on one shared
server-tick timeline, so a whole formation equips each piece on exactly the same tick.

The plugin runs entirely on the server. Players need no mods, resource packs or custom clients.

> **Testing status:** the build and the automated test suite (111 tests, MockBukkit + plain JUnit)
> pass. The plugin has **not** been tried on a real server with real clients yet, so what it looks
> and sounds like in game still needs checking — see [What still needs in-game testing](#what-still-needs-in-game-testing).

---

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [How it works](#how-it-works)
- [Commands](#commands)
- [Permissions](#permissions)
- [Configuration](#configuration)
- [Usage examples](#usage-examples)
- [Shared-tick synchronization](#shared-tick-synchronization)
- [Network latency limitations](#network-latency-limitations)
- [Inventory safety](#inventory-safety)
- [Building from source](#building-from-source)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Testing on a real server](#testing-on-a-real-server)
- [What still needs in-game testing](#what-still-needs-in-game-testing)
- [Roadmap](#roadmap)
- [License](#license)

---

## Requirements

| Component | Version |
|---|---|
| Server | [Paper](https://papermc.io/) **26.3** (built against `paper-api 26.3.build.231-stable`) |
| Java | **25** (required by Paper 26.x) |
| Client | Any vanilla Java Edition client for the server's version |

Only Paper is targeted. Folia is **not** supported: the plugin uses the global Bukkit scheduler,
not Folia's region schedulers.

## Installation

1. Download `SyncEquip-1.0.0.jar` (or [build it yourself](#building-from-source)).
2. Copy it into your server's `plugins/` folder.
3. Start or restart the server.
4. Edit `plugins/SyncEquip/config.yml` and `messages.yml` if you want to, then run `/syncequip reload`.

That's all. There are no dependencies to install.

## How it works

When a sequence runs, SyncEquip moves real items from the player's inventory into their equipment
slots, one step at a time:

| Tick (after start) | Step |
|---|---|
| 0 | Boots |
| 2 | Leggings |
| 4 | Chestplate |
| 6 | Helmet |
| 8 | Shield (offhand) |

At 20 TPS that is about 0.4 seconds from first to last piece. The interval (1–10 ticks) and the
order are configurable.

- **Real items, not visuals.** The exact item stack is moved, so enchantments, names, durability,
  trims, banner patterns and any other item data are kept.
- **All materials.** Leather, chainmail, iron, gold, diamond, netherite, turtle and copper armor,
  in any mix. Carved pumpkins, mob heads and elytra are ignored on purpose.
- **Main hand untouched.** The slot you are holding is never used as a source, so your weapon
  stays in your hand.
- **Empty slots by default.** If an armor slot or the offhand is already in use, the sequence
  refuses to start (or skips that step; see `missing-item-policy`). Existing equipment is never
  removed without you knowing.
- **Hotbar first.** If you carry two sets, the first match (hotbar, then main inventory) is used.

### Sound

With custom sounds on (the default), SyncEquip equips each piece silently through Paper's API
and plays **exactly one** sound per step at the player's position, so nearby players hear it from
the right place:

- Armor: the vanilla equip sound for that material (`item.armor.equip_iron`, `…_diamond`, and so on).
- Shield: vanilla makes no sound when a shield goes into the offhand, so SyncEquip plays
  `item.shield.block` (configurable) as the closing "CLACK".

With `sounds.enabled: false`, SyncEquip plays nothing and vanilla's own armor sounds play instead.
Either way a step never makes two sounds.

### Modes

- **Solo:** one player, `/syncequip solo` or the optional hotkey.
- **Squad:** many players on one shared timeline, started by the squad leader after a countdown
  (3… 2… 1… by default).

Both modes use the same sequence engine; a solo sequence is just a sequence with one participant.

### Hotkey: sneak + swap hands

The server can't see keyboard keys. It can see the *swap hands action* the client sends when you
press your swap key (F by default), and whether you are sneaking. With
`controls.sneak-swap-trigger: true`:

- **Sneak + F** starts a sequence, and that swap is cancelled so your offhand is left alone.
- **F without sneaking** swaps hands as normal.
- If you rebind the swap key in your controls, the trigger follows your new key.
- Pressing again while a sequence is running does nothing, and the cooldown applies.

With `sneak-swap-action: AUTO`, a squad leader starts the squad sequence instead of a solo one.

The trigger is off by default because it changes what sneak + F does on your server.

## Commands

Base command: `/syncequip` (aliases `/se`, `/sequip`). Every subcommand has tab completion.

| Command | Description |
|---|---|
| `/syncequip solo` | Start your personal equipment sequence |
| `/syncequip squad create` | Create a squad (you become the leader) |
| `/syncequip squad invite <player>` | Invite a player (leader only) |
| `/syncequip squad join <leader>` | Join a squad you were invited to |
| `/syncequip squad leave` | Leave your squad (leadership passes on if you were leader) |
| `/syncequip squad list` | List squad members |
| `/syncequip squad start` | Validate everyone and start the synchronized sequence (leader) |
| `/syncequip squad cancel` | Cancel the running squad sequence (leader) |
| `/syncequip squad transfer <player>` | Make another member the leader |
| `/syncequip settings` | Show the settings currently in effect |
| `/syncequip reload` | Reload `config.yml` and `messages.yml` |
| `/syncequip help` | Show command help |

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `syncequip.use` | everyone | `/syncequip solo`, `/syncequip settings` |
| `syncequip.hotkey` | everyone | The sneak + swap trigger (if enabled in config) |
| `syncequip.squad` | everyone | All `/syncequip squad …` management commands |
| `syncequip.squad.start` | everyone | Starting and cancelling the sequence of a squad you lead |
| `syncequip.reload` | op | `/syncequip reload` |
| `syncequip.bypass.cooldown` | op | Ignore the cooldown |
| `syncequip.admin` | op | Start or cancel the sequence of any squad you are in; includes reload and cooldown bypass |

## Configuration

`plugins/SyncEquip/config.yml` (every option is explained in the file itself):

```yaml
equipment:
  interval-ticks: 2            # 1-10 ticks between steps
  order: [BOOTS, LEGGINGS, CHESTPLATE, HELMET, SHIELD]
  require-empty-slots: true    # false = swap the worn piece back into the source slot
  missing-item-policy: ABORT   # ABORT or SKIP
  lock-inventory-during-sequence: true

solo:
  countdown-seconds: 0

synchronization:
  countdown-seconds: 3         # 0-10
  max-squad-size: 30           # no built-in upper limit
  invite-expiry-seconds: 60

sounds:
  enabled: true
  use-vanilla-armor-sounds: true
  armor-fallback-sound: minecraft:item.armor.equip_generic
  volume: 1.0
  pitch: 1.0
  shield: { sound: minecraft:item.shield.block, volume: 0.8, pitch: 1.2 }
  countdown: { enabled: true, sound: minecraft:block.note_block.hat }
  go: { enabled: false, sound: minecraft:block.note_block.snare }
  completion: { enabled: false, sound: minecraft:item.armor.equip_netherite }

visuals:
  particles-enabled: false
  actionbar-enabled: true
  countdown-display: TITLE     # TITLE, ACTIONBAR or NONE

controls:
  sneak-swap-trigger: false
  sneak-swap-action: SOLO      # SOLO or AUTO
  cooldown-seconds: 3

debug:
  log-sequence-ticks: false
```

**Validation.** Every value is checked on load. Values out of range are clamped, unknown values fall
back to the default, and unknown or duplicate `order` entries are dropped. Each problem is logged as
a `config.yml:` warning, and `/syncequip reload` lists the warnings in chat.

**Missing item policy.**

- `ABORT` — a solo sequence won't start unless every step can run. A squad won't start unless
  every member is ready, and the leader gets a list of who is missing what. If an item disappears
  mid-sequence (say another plugin moves it), only that player's sequence stops; pieces already
  on stay on.
- `SKIP` — unavailable steps are skipped. A player needs at least one usable piece to take part.

**Reloading** is always safe. Running sequences keep the settings they started with, and new ones
use the reloaded values.

All player-facing text lives in `messages.yml` in [MiniMessage](https://docs.advntr.dev/minimessage/format)
format. Set a message to `''` to turn it off.

## Usage examples

### Solo

```
/syncequip solo
```

With a full kit in your inventory and nothing worn, your boots, leggings, chestplate, helmet and
shield go on one after another over about 0.4 s. An action-bar progress bar
(`▰▰▰▱▱ Chestplate`) shows how far along you are.

### Squad

```
Leader:   /syncequip squad create
Leader:   /syncequip squad invite Alex
Leader:   /syncequip squad invite Sam
Alex:     /syncequip squad join Leader
Sam:      /syncequip squad join Leader
Leader:   /syncequip squad start
```

All members see the same `3`, `2`, `1` title. Then everyone gets their boots on the same server
tick, then their leggings, and so on. If anyone isn't ready, nothing starts:

```
Cannot start: 1 member(s) are not ready:
 • Sam: missing Leggings
```

## Shared-tick synchronization

- The plugin runs **one** repeating server task. Every tick it calls
  `TickSequenceScheduler.tick(Bukkit.getCurrentTick())`. Solo sequences, squad sequences and any
  number of concurrent squads all advance from this one loop. No player gets a timer of their own.
- Starting a squad creates **one** `SyncSequence` holding every member, one immutable
  `SequenceTimeline` (countdown beats, step offsets) and **one shared start tick**.
- When a step comes due, it runs for **every** participant inside the same `advance()` call, in
  the same server tick. Same-tick execution follows from how the code is built; it doesn't depend
  on timing luck.
- **Low TPS:** timing is counted in ticks, not milliseconds. When the server runs slower, the
  whole formation slows down together and the rhythm in ticks stays the same.
- **Lag spikes:** if the scheduler sees a step late, that step runs right away and the rest of
  the timeline shifts by the same amount. Only one tick offset is processed per server tick, so
  a spike never squashes several beats into one. Spacing between steps is kept and all members
  stay together.
- **Debug proof:** with `debug.log-sequence-ticks: true`, every step for every player is logged
  with its scheduled and actual tick:

  ```
  [debug] sequence #3 step 1/5 BOOTS      player=Leader           scheduled=4120 actual=4120 drift=0 result=EQUIPPED
  [debug] sequence #3 step 1/5 BOOTS      player=Alex             scheduled=4120 actual=4120 drift=0 result=EQUIPPED
  ```

  (This shows the format. The tick numbers above are made up for illustration.)

The timing core (`synchronization` package) has no Bukkit dependency, so it is unit-tested directly.

## Network latency limitations

The server guarantees that every member's equipment changes **in the same server tick**. Each
client then gets those updates over its own connection:

- Players with different pings see the change at slightly different real-world moments, about
  their difference in latency. A player with 40 ms and one with 120 ms are about 80 ms (1–2 ticks)
  apart *on their own screens*.
- What each viewer sees of the formation depends on that viewer's own connection, since all the
  updates were sent in the same tick.
- Packet loss, client frame rate and server network buffering can all add jitter.

SyncEquip doesn't try to correct for client latency. The server can't guarantee frame-perfect
timing on remote screens, and sending updates early per player would break the shared timeline.

## Inventory safety

- **Atomic moves.** Each step is one synchronous main-thread transaction. It re-reads the
  inventory, checks the destination and source again, then moves the item. Nothing can change
  the inventory in the middle of that.
- **Item count conserved.** No snapshot is ever restored and no item is ever recreated, so items
  can't be duplicated or lost (the tests count every item before and after).
- **Two checks.** The inventory is checked when a sequence is requested and again on the tick of
  the first step, after the countdown.
- **Brief lock.** Only during the ~0.4 s when equipment is changing, inventory clicks, drags,
  drops, hand swaps and right-click armor equipping are blocked. The countdown is not locked.
- **Interruptions:**

  | Event | Result |
  |---|---|
  | Cancel / leave / disconnect / death / world change **during the countdown** | Nothing was equipped; the inventory is untouched. Player is told so. |
  | The same **after equipping started** | Pieces already on stay on; the rest stay in the inventory. Player is told how many went on. |
  | Item moved away by another plugin mid-sequence | `ABORT`: that player stops; `SKIP`: that step is skipped. |
  | Squad leader disconnects | Leadership passes to the longest-standing member; the sequence continues for everyone else. |
  | Plugin disabled mid-sequence | All sequences stop; inventories stay as they are. |
  | Starting while already in a sequence | Refused. |

## Building from source

You need JDK 21 or newer to run Gradle. The build uses Gradle toolchains to fetch JDK 25
automatically if you don't have it.

```bash
git clone <your fork URL> syncequip
cd syncequip
./gradlew build
```

The plugin jar is written to `build/libs/SyncEquip-1.0.0.jar`. On Windows, use `gradlew.bat build`.

Pinned versions (in `build.gradle.kts` and `gradle/wrapper/gradle-wrapper.properties`):
Gradle 9.6.0, Paper API 26.3.build.231-stable, MockBukkit (v26.3) 4.119.0, JUnit 6.1.3, Java 25.

## Testing

```bash
./gradlew test
```

HTML report: `build/reports/tests/test/index.html`.

| Area | Test classes | What is covered |
|---|---|---|
| Timing core (plain JUnit) | `SequenceTimelineTest`, `TickSequenceSchedulerTest` | Offsets 0/2/4/6/8, countdown beats, 25- and 250-player shared ticks, identical offsets, cancellation before and after start, removal mid-sequence, concurrent squads, lag spikes, no collapsed beats, error isolation |
| Equipment (MockBukkit) | `EquipmentManagerTest`, `InventoryValidatorTest` | Moves, metadata kept (enchantments, name, damage), occupied slots, swap mode, missing items, main hand kept, mixed materials, stacked items, dead players |
| End to end (MockBukkit) | `SoloSequenceTest`, `SquadSequenceTest` | Real scheduler task: per-tick checks, custom interval and order, ABORT/SKIP, cooldown, item removed mid-sequence, death, disconnect, plugin disable, inventory lock, 10- and 30-player squads on one tick, debug-log check, countdown revalidation, leader handover, concurrent squads |
| Commands and hotkey | `CommandTest`, `HotkeyTest` | Squad lifecycle, invites, permissions, unknown players, max size, reload warnings, console sender, tab completion, sneak + swap behavior |
| Config and audio | `ConfigManagerTest`, `EquipmentSoundManagerTest`, `SquadManagerTest`, `CooldownTrackerTest` | Validation and clamping, sound selection per material, no double sounds when custom sounds are off |

MockBukkit simulates the server, but it does not render anything and does not play sounds to a
client. The tests check which sound *would* be played, not what it sounds like.

CI (`.github/workflows/build.yml`) runs `./gradlew build` on JDK 25 for every push and pull
request and uploads the jar as an artifact.

## Testing on a real server

A manual checklist is in [docs/TESTING.md](docs/TESTING.md), and [docs/MULTIPLAYER_TESTING.md](docs/MULTIPLAYER_TESTING.md)
explains how to run a local Paper server and let a friend join. Keep the test server folder outside this repository.

## Troubleshooting

| Problem | Fix |
|---|---|
| `Cannot start: already wearing Boots` | Take the piece off, or set `equipment.require-empty-slots: false` to swap it. |
| `Cannot start: missing Shield` | Put the item anywhere in your inventory **except** the slot you are holding. Or set `missing-item-policy: SKIP`. |
| Sneak + F does nothing | Set `controls.sneak-swap-trigger: true`, check `syncequip.hotkey`, check the cooldown. Make sure you are actually sneaking when you press the key. |
| Sounds play twice | Shouldn't happen. If another plugin also plays equip sounds, set `sounds.enabled: false`. |
| No sound at all | Check `sounds.enabled`, the volume, and that sound keys are valid `namespace:path` keys. Players also need their *Players* sound slider turned up. |
| Members' armor looks a tick apart | This is network latency on their end. See [Network latency limitations](#network-latency-limitations). Turn on `debug.log-sequence-ticks` to confirm the server ran the step on one tick. |
| Plugin fails to load | You need Paper 26.3 and Java 25. Check the server log for the error. |

## What still needs in-game testing

Automated tests can't verify the following yet. They need a real server and real clients:

- How the sequence sounds: the volume and pitch balance of the per-material armor sounds and the
  `item.shield.block` "CLACK", and how they mix with 10+ players.
- How it looks to other players: armor appearing piece by piece on other players' models, the
  title/action-bar countdown, and the optional particles.
- The sneak + swap trigger with a real client, including rebound keys.
- Behavior at real low TPS and with real network latency.
- Interaction with other inventory, armor or combat plugins.
- Edge cases MockBukkit models loosely, such as death drops and respawn.

## Roadmap

These are possible future features, not commitments. The current design leaves room for them:
`SequenceTimeline` is just data, and `SequenceCallbacks` separates timing from what happens on
each step.

- Saved equipment loadouts
- Custom synchronized patterns (alternating ranks, waves)
- Preset squad formations
- Equipment *removal* sequences
- Per-step and per-material sound customization
- A public API for other plugins
- An optional client-side mod for richer visuals

## License

[MIT](LICENSE)
