# Testing with a friend

How to run the local Paper server and let a friend with Minecraft Java Edition join it.

The server lives **outside** this repository, in `D:\syncequip-test-server`. The world, logs and
server jar are never committed.

## Important: online mode

The server uses `online-mode=true`. That means **every player needs a legitimate Minecraft: Java
Edition account** and has to be signed in to it. This is the safe default, and it is what the real
game does.

If the host (the person running the server) doesn't own the game, they can still run the server and
use the console, but they can't join as a player. In that case you need **two friends** to test
squads, because squads need at least two players. The console can run `/syncequip help`, `settings` and `reload`,
but `solo` and `squad` are for players only. It can also `op` people, give items and read the debug log.

## Host steps

1. Install Java 25. The Gradle build already downloaded one, and `start-server.bat` finds it
   automatically. If you want your own, install any Java 25 JDK and set `JAVA25_HOME` to it.
2. Open `D:\syncequip-test-server\eula.txt` and change `eula=false` to `eula=true`.
   This means you agree to the [Minecraft EULA](https://aka.ms/MinecraftEULA).
3. Double click `start-server.bat`. The first start takes a minute while it creates the world.
4. Wait for `Done (...)! For help, type "help"`. Leave the window open.
5. In that console, give people operator rights (use exact account names):

   ```
   op FriendName
   op YourName
   ```

6. To update the plugin after changing code:

   ```bash
   cd D:\syncequip
   ./gradlew build
   copy build\libs\SyncEquip-1.0.0.jar D:\syncequip-test-server\plugins\
   ```

   Then type `stop` in the server console and start it again. (`/reload` is not safe for plugins.)

A harmless `oshi ... Unable to locate English counter names` error may appear at startup on Windows. It comes from Minecraft's
system report, not from SyncEquip, and can be ignored.

Stop the server with `stop` in the console, not by closing the window, so the world saves.

## Letting a friend connect

Pick one option. Do the least risky one that works for you.

### A. Same home network (easiest)

Your friend is on the same Wi-Fi or router. They add a server in Minecraft with your PC's local address.

- Your current local address: `192.168.1.219` (check with `ipconfig` if it changed)
- In Minecraft: **Multiplayer → Add Server → Server Address** `192.168.1.219`
  (the default port 25565 is assumed)

If Windows Firewall asks about Java the first time, allow it for **Private networks**. If it never asked
and the friend can't connect, run this once in an **Administrator** PowerShell:

```powershell
New-NetFirewallRule -DisplayName "Minecraft test server" -Direction Inbound -Protocol TCP -LocalPort 25565 -Action Allow -Profile Private
```

### B. Over the internet with a tunnel (no router changes)

Use a tunnel service so you don't have to open ports on your router. A few exist, for example
[playit.gg](https://playit.gg/) (made for Minecraft) or a VPN like Tailscale (friend installs it too).

1. Follow the service's own instructions to point a public address at `localhost:25565`.
2. Send your friend the address it gives you.

Check the service's terms and what it exposes before using it. Tunnels make your test server reachable
from the internet.

### C. Port forwarding (most exposed)

Forward TCP port 25565 on your router to your PC's local address, then give your friend your public IP.
This exposes your home IP address. Prefer option A or B.

### Either way

- Friends need the same Minecraft version as the server. Paper 26.3 is for Minecraft Java Edition 26.3.
- For a small private test, consider turning on the whitelist so only your friend can join:

  ```
  whitelist add FriendName
  whitelist on
  ```

- Only share the address with people you trust, and stop the server when you're done.

## What to do once you're both in

1. Run `op` for both of you in the server console (see above).
2. Give each player the kit from [TESTING.md](TESTING.md).
3. Work through the checklist in [TESTING.md](TESTING.md). Sections 1 to 3 need one player, sections 4 to 7 need two.
4. Turn on `debug.log-sequence-ticks` for the squad tests so the log can prove same-tick execution.
5. Write results in the **Findings** table, and send me the log lines for anything that failed.

## Where things are

| What | Where |
|---|---|
| Server folder | `D:\syncequip-test-server` |
| Start script | `D:\syncequip-test-server\start-server.bat` |
| Plugin jar used by the server | `D:\syncequip-test-server\plugins\SyncEquip-1.0.0.jar` |
| Plugin config | `D:\syncequip-test-server\plugins\SyncEquip\config.yml` (created on first start) |
| Server log | `D:\syncequip-test-server\logs\latest.log` |
| World | `D:\syncequip-test-server\world` |
