# SyncEquip

SyncEquip is a Minecraft Java plugin that lets players synchronize armor and weapon equipping across entire squads. Built as a personal Java development project, it explores multiplayer synchronization, inventory management, and customizable equipment mechanics while making coordinated gameplay more fun and creative.

## Installation

**You need:** a [Paper](https://papermc.io/) **26.3** server running **Java 25**. Players need no mods or resource packs. Folia is not supported.

1. Get the plugin jar, `SyncEquip-1.0.0.jar`. Either download it, or build it yourself:
   ```
   ./gradlew build
   ```
   (`gradlew.bat build` on Windows). The jar is written to `build/libs/SyncEquip-1.0.0.jar`.
2. Stop your server, or leave it running if you will reload afterwards.
3. Copy the jar into the server's `plugins/` folder.
4. Start the server. SyncEquip creates `plugins/SyncEquip/` with `config.yml`, `messages.yml` and `squads.yml`.
5. Check the console for `SyncEquip enabled`. In game, run `/syncequip help` to confirm it works.
6. Optional: edit `config.yml` or `messages.yml`, then run `/syncequip reload`.

There are no other dependencies. To update, replace the jar while the server is stopped; your config and saved squads are kept.

### Permissions

| Permission | Default | Grants |
|---|---|---|
| `syncequip.use` | everyone | `/syncequip solo`, `/syncequip settings` |
| `syncequip.squad` | everyone | Squad commands |
| `syncequip.squad.start` | everyone | Start, cancel and unequip the squad you lead |
| `syncequip.squad.manage` | everyone | Change speed, loadout and joining block of a squad you lead |
| `syncequip.hotkey` | everyone | The sneak + swap trigger, if enabled in config |
| `syncequip.demo` | op | Demo mode and the guided tour |
| `syncequip.reload` | op | `/syncequip reload` |
| `syncequip.bypass.cooldown` | op | Ignore the cooldown |
| `syncequip.admin` | op | Run and configure any squad |

## Commands

Base command: `/syncequip` (aliases `/se`, `/sequip`). Every subcommand has tab completion.

### General

| Command | Description |
|---|---|
| `/syncequip help [page or name]` | Paged help |
| `/syncequip solo` | Start your personal equipment sequence |
| `/syncequip unequip` | Take your own gear off in reverse order |
| `/syncequip settings` | Show the settings currently in effect |
| `/syncequip reload` | Reload `config.yml` and `messages.yml` |

### Squads

| Command | Description |
|---|---|
| `/syncequip squad create [name]` | Create a squad (you become the leader) |
| `/syncequip squad invite <player>` | Invite a player (leader) |
| `/syncequip squad join <squad or leader>` | Join a squad you were invited to |
| `/syncequip squad leave` | Leave your squad |
| `/syncequip squad list` | List squad members |
| `/syncequip squad info` | Show members, speed, loadout and joining block |
| `/syncequip squad transfer <player>` | Make another member the leader |
| `/syncequip squad disband` | End the squad and delete its saved settings (leader) |
| `/syncequip squad start` | Start the synchronized equip sequence (leader) |
| `/syncequip squad cancel` | Cancel the running sequence (leader) |
| `/syncequip squad unequip` | Everyone takes their gear off together, in reverse order (leader) |
| `/syncequip squad speed [ticks\|default]` | Ticks between pieces, 1 to 10 (leader) |

### Joining block

| Command | Description |
|---|---|
| `/syncequip squad block set [block]` | Use a block material, or the block under your feet if none is given |
| `/syncequip squad block remove` | Remove the joining block |
| `/syncequip squad block info` | Show the material and world |
| `/syncequip squad block enable` / `disable` | Turn joining by stepping on the block on or off |

### Loadout

| Command | Description |
|---|---|
| `/syncequip squad loadout show` | Show what each slot needs |
| `/syncequip squad loadout set <slot> <item>` | Set a slot: `mainhand`, `offhand`, `helmet`, `chestplate`, `leggings` or `boots`. Use `none` to skip a slot |
| `/syncequip squad loadout optional <slot> on\|off` | Skip the slot quietly when the item is missing |
| `/syncequip squad loadout order <slots...>` | Slots that go first |
| `/syncequip squad loadout reset` | Back to the defaults |

### Demo and tour (op)

| Command | Description |
|---|---|
| `/syncequip demo spawn <count>` | Spawn 1 to 20 husks in a row in front of you |
| `/syncequip demo start` | Equip the husks in sync |
| `/syncequip demo repeat <times>` | Run the demo several times in a row |
| `/syncequip demo stop` | Stop a running demo |
| `/syncequip demo reset` | Remove all husk equipment |
| `/syncequip demo clear` | Remove all husks |
| `/syncequip demo speed <ticks>` | Ticks between pieces for the next run |
| `/syncequip demo tour [start\|pause\|resume\|restart\|stop]` | Guided walkthrough with test players |
