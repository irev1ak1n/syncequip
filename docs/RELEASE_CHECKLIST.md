# Release checklist

Use this before publishing a SyncEquip release on GitHub.

**Current status: not gameplay-verified.** The build and the 111 automated tests pass, and the plugin
loads and registers its commands on a Paper 26.3 server. Nobody has yet joined with a real client, so
sound, visuals, squad feel and the hotkey are unchecked. Do not describe a release as gameplay-tested
until the in-game section below is complete.

## 1. Code and build

- [ ] `git status` is clean and you are on `main`
- [ ] Version updated in `build.gradle.kts` (`version = "x.y.z"`)
- [ ] `api-version` in `plugin.yml` still matches the Paper version you tested on
- [ ] `./gradlew clean build` succeeds on a fresh clone
- [ ] All tests pass (see `build/reports/tests/test/index.html`)
- [ ] CI is green on the release commit
- [ ] The jar `build/libs/SyncEquip-x.y.z.jar` opens, and `plugin.yml` inside shows the right version

## 2. Server smoke test

- [ ] Fresh Paper server, jar in `plugins/`, starts with no SyncEquip errors
- [ ] `config.yml` and `messages.yml` are created on first start
- [ ] `/syncequip help` works, and Tab completion works
- [ ] `/syncequip reload` works and reports no warnings with the default config
- [ ] Plugin disables cleanly on `stop`

## 3. In-game verification (needs real players)

Work through [QUICK_CHECKLIST.md](QUICK_CHECKLIST.md), and the full [TESTING.md](TESTING.md) where possible.

- [ ] Solo sequence looks and sounds right
- [ ] Squad sequence with at least 3 players is visibly in step
- [ ] Debug log shows one tick per step for all players (`scripts/check-sync-log.ps1`)
- [ ] Inventory safety cases pass: disconnect, death, cancel, item removed, world change
- [ ] Sneak + swap hotkey works with a real client
- [ ] Sound volumes and pitch are acceptable
- [ ] No unexplained errors in `logs/latest.log`
- [ ] Results recorded (who tested, Minecraft/Paper build, date, player count, pings)
- [ ] Any failures are fixed or listed under known issues

If a box in this section is unchecked, label the release as **pre-release** and say what was not tested.

## 4. Documentation

- [ ] README versions (Paper, Java) match what was tested
- [ ] README "What still needs in-game testing" is updated to match reality
- [ ] Config options in the README match `config.yml`
- [ ] Changelog or release notes written
- [ ] No screenshots, videos or numbers in the docs unless they are real. Add recorded demos only after they exist

## 5. Repository hygiene

- [ ] No server files, worlds, logs, `.jar` files or personal data committed (`git ls-files` looks clean)
- [ ] `LICENSE` present
- [ ] `.gitignore` covers build output and local servers

## 6. Publish

- [ ] Tag the commit: `git tag -a vX.Y.Z -m "SyncEquip X.Y.Z"` and push the tag
- [ ] Create the GitHub release from the tag
- [ ] Attach `SyncEquip-X.Y.Z.jar`, and optionally its SHA-256 (`certutil -hashfile SyncEquip-X.Y.Z.jar SHA256`)
- [ ] Tick **pre-release** unless section 3 is fully done
- [ ] Release notes state the tested Paper build, Java 25, and what is and isn't verified in game

## Release notes template

```
## SyncEquip X.Y.Z

Synchronized, piece by piece armor equipping for solo players and squads on Paper.

Requires: Paper 26.3, Java 25.

Verified: automated tests (111), server startup and command registration.
Not yet verified in game: <list what remains from section 3>.

Install: put SyncEquip-X.Y.Z.jar in the server's plugins folder and restart.
```
