# Bot test (no Minecraft account needed)

Runs SyncEquip against simulated players built with [Mineflayer](https://github.com/PrismarineJS/mineflayer),
on an isolated Paper 26.3 server that only listens on `127.0.0.1`. It checks the plugin's logic
and timing end to end with real network connections. It cannot show what the game looks or sounds like.

## What it does

1. Starts the isolated bot server (`..\..\syncequip-bot-server`, separate from the secure test server).
2. Connects 2 to 10 bots (`Bot1` to `BotN`) using offline mode on localhost.
3. Lines them up and gives each a netherite kit with the `syncequip_test` datapack. Equipment slots start empty.
4. Bot1 creates a squad, invites the others, they join, and Bot1 runs `/syncequip squad start`.
5. Checks the result two ways and prints a report (also saved to `bot-test\reports\latest.json`).

| Check | Source | Strength |
|---|---|---|
| Every step ran on **one server tick** for all bots, offsets 0, 2, 4, 6, 8, no drift | The plugin's own `[debug]` log lines | Authoritative, tick based |
| Every bot received equipment packets for every bot and itself | Bot clients | Solid |
| Packet arrival spread and the roughly 400 ms span | Bot clients, wall clock | Soft. Reported as WARN, not FAIL, because a busy PC adds noise |
| Final slots hold the netherite pieces and shield, the sword stayed in hand, nothing is left behind | Bot inventories | Solid |

## Quick start

```bat
cd D:\syncequip
gradlew.bat build
powershell -File bot-test\setup-bot-server.ps1 -AcceptEula
bot-test\start-bot-test.bat 5
```

- `setup-bot-server.ps1` is a one time step. It downloads the official Paper jar (checksum verified), copies
  the plugin and datapack and writes localhost only settings. `-AcceptEula` means you accept the
  [Minecraft EULA](https://aka.ms/MinecraftEULA).
- `start-bot-test.bat [bots] [keep]` runs the test. `bots` is 2 to 10 (default 5). The first run installs the
  npm dependencies with install scripts turned off.
- With `keep`, the server and bots stay up, a status page is served at <http://localhost:3008>, and pressing
  Enter in the window gives everyone a fresh kit and runs the sequence again.
- `bot-test\stop-bot-test.bat` stops the server and runner if something is left running.

Needs Node.js 22 or newer. Java 25 is found the same way as for the main test server.

## Safety

- The bot server is on **offline mode on purpose**, because bots have no Minecraft account. It is only safe
  because it is bound to `127.0.0.1`. The runner checks this on every start (127.0.0.1 must accept a
  connection and this PC's LAN address must refuse one) and stops if not.
- Never copy this `server.properties` to a server other people can reach.
- The secure server in `D:\syncequip-test-server` (online mode, whitelist) is untouched.
- The bots never use real Microsoft or Mojang credentials.

## The `@wp2508/mineflayer` fork

The official `mineflayer` on npm (4.39.0) does not list Minecraft 26.3. The `@wp2508/mineflayer` fork does
(`testedVersions` includes `26.3`, protocol 777, data version 5023, which matches Paper 26.3), so the test pins
**`@wp2508/mineflayer@4.42.6`**.

What I checked before installing and after (this is a quick review, not a full audit):

- Single maintainer (`wp2508`), package created in August 2026, source at `github.com/wupengabc/mineflayer`.
  That is a small, new project, so treat it as unvetted software. It is only ever run against localhost here.
- It replaces several Prismarine packages with its own scoped forks (`minecraft-data`, `minecraft-protocol`,
  `protodef`, `prismarine-chat`, `-block`, `-chunk`, `-physics`, `-registry`) via npm aliases.
- 109 packages installed. **No** `preinstall`, `install` or `postinstall` scripts, and no native (`binding.gyp`) code.
  Dependencies are installed with `ignore-scripts=true` (see `.npmrc`) and versions are exact.
- A pattern search of the forked packages found no unexpected network hosts or shell execution at runtime.
  Matches were the same kinds as upstream (`eval` in protodef's compiler, `net`/`dns` in the protocol
  library, a dev only `child_process` in minecraft-data's tools folder that the test never runs).
- Not checked: the full git history, the exact diff against upstream, or `npm audit` findings in transitive packages.

## Prismarine Viewer: tried, does not work with 26.3

`prismarine-viewer@1.33.0` is installed and `--viewer` still starts it (forced onto localhost only), but it is
**not usable for 26.3**:

- Its browser page shows only a blank sky colour and the message `null is not supported`, because the viewer's bundled client
  supports Minecraft up to 1.21.4 and rejects 26.3.
- There are no 26.3 textures in the package (`textures/26.3.png` returns 404).
- It also needs the native `canvas` module on the server side. That module downloads a binary at install time, so
  it is replaced with a small stub (`stubs/canvas.js`) instead of being installed.
- Showing the world with a wrong version would draw wrong blocks, so I did not fake it.

As a substitute there is a small status page (`lib/dashboard.js`, <http://localhost:3008>, localhost only). It lists
each bot's position, held item and the five equipment slots as that bot's own client sees them, updating live.
It is a data table, not a picture of the game.

## What bots can and cannot validate

**Can validate (and the report does):**

- Squad create, invite, join and start work through real commands over a real connection
- All steps run on the same server tick for every member, in order, 2 ticks apart
- The server sends every member's equipment change to every nearby client, and to the player themself
- The kit datapack works on real players, the main hand item stays, and the final inventory is correct
- The plugin survives a 10 player squad without drift on this PC

Along the way a real bug in the test harness (not the plugin) showed up: sending about 20 commands in a
second made vanilla kick the leader for spamming. The plugin handled that leader leaving mid sequence correctly,
and the other nine bots still equipped on the shared ticks.

**Cannot validate (needs a real Minecraft client, not tested):**

- Equip animations, how the armor and shield look on the character, and what other players see
- **Every sound**: whether the clanks play, their volume, pitch and direction, and whether any step makes a double sound.
  Bots have no audio output, and this harness does not look at sound packets
- The title and action bar countdown and progress bar rendering, and the particles
- Sneak + swap hands (Shift + F). Bots do not press keys, and the hotkey was not exercised here
- Client prediction, frame timing and how ping makes steps look on different screens
- Whether it *feels* synchronized to a human

Treat a PASS here as "the server did the right thing on the right ticks." It is not a claim that the in game
animations or sounds work. That still needs a real client, see [../docs/QUICK_CHECKLIST.md](../docs/QUICK_CHECKLIST.md).

## Files

| File | Purpose |
|---|---|
| `run-test.js` | The test runner |
| `lib/server.js` | Starts and stops the server, localhost check |
| `lib/analyze.js` | Server tick and packet checks |
| `lib/dashboard.js` | Localhost status page |
| `stubs/canvas.js` | Stand-in for the native canvas module |
| `setup-bot-server.ps1` | Creates the isolated server |
| `start-bot-test.bat`, `stop-bot-test.bat` | Windows start and stop |
| `reports/` | Latest report and debug log lines (not committed) |

## Troubleshooting

| Problem | Fix |
|---|---|
| `The isolated bot server does not exist yet` | Run `setup-bot-server.ps1 -AcceptEula` |
| `Refusing to run: server-ip must be 127.0.0.1` | Fix `server.properties` in the bot server folder |
| Bots fail to spawn | Check `..\..\syncequip-bot-server\logs\latest.log` and that port 25599 is free |
| A WARN on packet timing | Close other heavy programs and rerun. The server tick checks are the ones that matter |
| Leftover java or node process | Run `stop-bot-test.bat` |
