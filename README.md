# SyncEquip

SyncEquip is a Minecraft Java plugin that lets players synchronize armor and weapon equipping across entire squads. Built as a personal Java development project, it explores multiplayer synchronization, inventory management, and customizable equipment mechanics while making coordinated gameplay more fun and creative.

## Download

**[Download SyncEquip from GitHub Releases](https://github.com/irev1ak1n/syncequip/releases/latest)**

On that page, scroll to **Assets** and click `SyncEquip-1.1.2.jar`. That file is the whole plugin.

## Who installs it?

Only the **server owner or admin** installs SyncEquip, once, on the server. **Players don't download or install anything.** They join as normal with a regular Minecraft Java client.

## What you need

- A **Paper** server for **Minecraft 26.3** (get Paper at [papermc.io](https://papermc.io/)). It will not run on other Minecraft versions, and Spigot and Folia are not supported.
- **Java 25** on the server (Paper 26.x needs it; most hosting panels let you pick it).

## Install it

1. Download `SyncEquip-1.1.2.jar` (see above).
2. Put it in your server's **`plugins`** folder:
   - **Hosting panel** (for example Pterodactyl or Apex): open the **File Manager**, open the `plugins` folder, click **Upload** and choose the jar.
   - **Your own computer or server:** copy the file into the `plugins` folder next to `paper.jar`.
3. **Restart** the server (use the Restart button, or type `stop` in the console and start it again).
4. Check it worked: in game or in the console, run `/plugins`. **SyncEquip** should be green. Then run `/syncequip help`.

SyncEquip makes its own settings in `plugins/SyncEquip/`. Updating later: stop the server, replace the old jar with the new one, and start again. Your settings and squads are kept.

## Your first squad

You need to be an operator, or have the squad permissions (everyone has them by default, see [Permissions](#permissions)). Every player taking part needs a sword, a shield and a full set of armor in their inventory. The commands start with `/syncequip`, or `/se` for short.

1. **Create a squad:** `/se squad create Alpha`. You are the leader.
2. **Pick a joining block:** place an obsidian block, stand on it and run `/se squad block set`. (Or just run `/se squad block set obsidian` to use every obsidian block.) Anyone who steps onto obsidian now joins your squad.
3. **Get your friends to step on it.** You can also invite people with `/se squad invite <player>`.
4. **Start:** run `/se squad start`. After a 3 second countdown, everyone puts their armor on piece by piece, all on the same moment. `/se squad unequip` takes it all off again.

Missing items are swapped in from the inventory automatically, and anyone who can't take part is skipped with a reason while everyone else goes on.

### Want to see it first?

Operators can run `/se demo spawn 5` and `/se demo start` for a quick demo with dummy mobs, no friends needed.

Want to build it yourself, or run the guided tour with test players? See the [Developer Guide](docs/DEVELOPER_GUIDE.md).

## Permissions

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
