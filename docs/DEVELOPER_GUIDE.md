# Developer Guide

For server owners, the [README](../README.md) is all you need. This page is for building and changing the plugin.

## Requirements

- JDK 21 or newer to run Gradle. The build uses Gradle toolchains to fetch JDK 25 automatically if you don't have it.
- Git.

## Building

```bash
git clone https://github.com/irev1ak1n/syncequip.git
cd syncequip
./gradlew build
```

On Windows use `gradlew.bat build`. The plugin jar is written to `build/libs/SyncEquip-<version>.jar`.
It contains only the plugin classes, `plugin.yml`, `config.yml` and `messages.yml`. Paper's API is `compileOnly`, so
nothing is bundled and no other dependency is needed at runtime.

Pinned versions (in `build.gradle.kts` and `gradle/wrapper/gradle-wrapper.properties`):
Gradle 9.6.0, Paper API 26.3.build.231-stable, MockBukkit (v26.3) 4.119.0, JUnit 6.1.3, Java 25.

## Tests

```bash
./gradlew test
```

The automated suite uses MockBukkit and plain JUnit. The report is in `build/reports/tests/test/index.html`.
CI (`.github/workflows/build.yml`) runs `./gradlew build` on JDK 25 for every push and pull request.

## Testing on a real server

- [TESTING.md](TESTING.md): full manual checklist.
- [QUICK_CHECKLIST.md](QUICK_CHECKLIST.md): one page for testers.
- [MULTIPLAYER_TESTING.md](MULTIPLAYER_TESTING.md): running a local Paper server and letting friends join.
- [`bot-test/`](../bot-test/README.md): bot based checks that need no Minecraft account, plus `start-tour.bat` for the
  guided tour (`/se demo tour`). They check server side timing and inventory results, not sounds or animations.

Keep test servers outside this repository.

## Releasing

1. Update `version` in `build.gradle.kts` and add the changes to `CHANGELOG.md`.
2. `./gradlew clean build`, then check the version inside the jar's `plugin.yml`.
3. Tag and publish: `gh release create vX.Y.Z build/libs/SyncEquip-X.Y.Z.jar --title "SyncEquip X.Y.Z" --notes-file <notes>`.
4. Upload the same jar to Modrinth using [MODRINTH.md](MODRINTH.md).
5. Update the version and links in the README. See also [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).
