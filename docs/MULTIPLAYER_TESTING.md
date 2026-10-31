# Testing with a friend

How to run the local Paper server and let friends with Minecraft Java Edition join it.

The server lives **outside** this repository, in `D:\syncequip-test-server`. The world, logs and
server jar are never committed. Nothing here has been tested with a real client yet.

## Online mode and accounts

The server runs with `online-mode=true`, so every player needs a legitimate Minecraft: Java Edition
account and must be signed in. If the host doesn't own the game, they can run the server and use the
console but can't join. Squads need at least two players, so in that case two friends are needed.

The console can run `/syncequip help`, `settings` and `reload`. `solo` and `squad` are for players only.

## Current server setup

| Setting | Value |
|---|---|
| `online-mode` | `true` |
| `white-list` and `enforce-whitelist` | `true` (only listed players can join) |
| Memory | 2 GB minimum, 4 GB maximum (`start-server.bat`) |
| SyncEquip `debug.log-sequence-ticks` | `true` (logs every step for every player) |
| Test kit datapack | `syncequip_test`, installed in `world/datapacks` |

The whitelist starts **empty**. You must add each player's exact Minecraft name before they can join.

## Host steps

1. Double click `D:\syncequip-test-server\start-server.bat` and wait for `Done (...)!`.
2. In the server console, add each tester (exact account names) and make them operator:

   ```
   whitelist add FriendName
   op FriendName
   ```

   Ops are needed so testers can run the kit function and `/kill` and `/tick` for the checklist.
   Only op people you trust.
3. Everyone joins, then each tester runs `/function syncequip_test:kit` (see below).
4. Follow [QUICK_CHECKLIST.md](QUICK_CHECKLIST.md).
5. Stop the server with `stop` in the console, not by closing the window, so the world saves.

To update the plugin after code changes:

```bash
cd D:\syncequip
./gradlew build
copy build\libs\SyncEquip-1.0.0.jar D:\syncequip-test-server\plugins\
```

Then `stop` and start the server again. `/reload` is not safe for plugins.

## The test kit

The datapack adds three commands for ops:

| Command | What it does |
|---|---|
| `/function syncequip_test:kit` | Clears your inventory, then gives a netherite sword in hotbar slot 1, and netherite boots, leggings, chestplate, helmet and a shield in the first five slots of the main inventory. All armor slots and the offhand are empty. |
| `/function syncequip_test:kit_all` | The same, for every online player at once. |

Use `kit_all` before each squad run so everyone starts equal. Keep hotbar slot 1 selected, because
SyncEquip never takes from the slot you are holding.

The datapack source is in the repo at `testing/datapack/syncequip_test`. To install it on a new
test world, copy that folder into `<world>/datapacks/` and restart the server. The files were
confirmed to load on the server (`Found new data pack file/syncequip_test` and `Running function`
appear in the log), but they have not yet been run on a real player.

## Connecting remote friends

The goal is to let a few named people connect without opening your home network more than necessary.
Whichever option you pick, the whitelist plus online mode means a stranger who finds the address still
can't join, because the server checks each account against the list.

### Recommended: Tailscale (nothing exposed to the public internet)

[Tailscale](https://tailscale.com/) makes a private network between your devices and the people you
share with. No router changes and no public address.

1. You and the friend each install Tailscale and sign in.
2. In Tailscale's admin page, **share** your PC with the friend. Sharing one machine gives them access to that
   machine only, not your whole network. Follow Tailscale's own instructions for the current steps.
3. The friend uses your PC's Tailscale name or IP (shown in Tailscale) as the server address in Minecraft.

The friend has to install software, and the free plan has limits. Check Tailscale's terms and docs.

### Alternative: playit.gg tunnel (friend installs nothing)

[playit.gg](https://playit.gg/) gives your local server a public address through a tunnel, so you don't touch
your router. Anyone who has the address can reach the Minecraft port, so rely on the whitelist, keep
`online-mode=true`, and stop the tunnel when you're done. It is less private than Tailscale.

### Same home network

If your friend is on the same Wi-Fi or router, use your PC's local address
(`ipconfig`, currently `192.168.1.219`) as the server address.
If Windows Firewall prompts for Java, allow it on **Private networks** only.

### Not recommended

Port forwarding on your router publishes your home IP address and leaves the port open. Avoid it unless you
understand the risk.

I haven't changed any firewall, router or authentication settings. Any of the steps above that touch them
are yours to do and approve.

## Recording a short demo

Ask the tester with the best view to record. Keep it under a minute.

- **Windows Game Bar:** press `Win + Alt + R` to start and stop recording. Files go to `Videos\Captures`.
  Minecraft must be in the foreground. Windows may ask you to enable Game Bar the first time.
- **OBS Studio** (free): add a *Game Capture* or *Window Capture* source and record. Enable desktop audio
  so the sounds are captured.
- Tips: stand the squad in a line, record from the side so you can see the pieces appear, use the in-game
  clock or note the time, and leave sounds on. Say the squad size and your ping out loud or in the file name.
- A video shows what one client displayed. It does not prove server timing. For that, use the log check below.
- Do not post videos or claims about the plugin until they are real recordings of real tests.

## Where the logs are

| Log | Location |
|---|---|
| Server (host PC) | `D:\syncequip-test-server\logs\latest.log` (older ones are zipped next to it) |
| Minecraft client (each tester) | `%APPDATA%\.minecraft\logs\latest.log` |

SyncEquip debug lines look like this (the numbers are examples):

```
[debug] sequence #1 step 1/5 BOOTS player=Alice scheduled=4120 actual=4120 drift=0 result=EQUIPPED
```

To summarize them and check that all players matched on each step:

```powershell
powershell -File D:\syncequip\scripts\check-sync-log.ps1
powershell -File D:\syncequip\scripts\check-sync-log.ps1 -Out sync-report.txt
```

When reporting a problem, send the time, what you did, your ping and the relevant lines from
`latest.log`. Client logs are only needed for client-side errors.

## Where things are

| What | Where |
|---|---|
| Server folder | `D:\syncequip-test-server` |
| Start script | `D:\syncequip-test-server\start-server.bat` |
| Plugin jar | `D:\syncequip-test-server\plugins\SyncEquip-1.0.0.jar` |
| Plugin config | `D:\syncequip-test-server\plugins\SyncEquip\config.yml` |
| World | `D:\syncequip-test-server\world` |

A harmless `oshi ... Unable to locate English counter names` error may appear at startup on Windows. It
comes from Minecraft's system report, not from SyncEquip.
