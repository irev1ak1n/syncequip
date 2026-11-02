package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.squad.SquadManager;
import dev.syncequip.squad.SquadProfile;
import dev.syncequip.squad.SquadStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Joining a squad by stepping onto a block of the squad's material, anywhere in the world. */
class BlockTest extends V11TestBase {

    private Block put(Material material, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        return block;
    }

    private PlayerMock leaderOf(String squad, String material) {
        PlayerMock leader = leaderWithSquad(squad + "Boss", squad);
        run(leader, "squad", "block", "set", material);
        return leader;
    }

    // ------------------------------------------------------------------ the main behaviour

    @Test
    void obsidianAtManyDifferentCoordinatesJoinsTheSameSquad() {
        PlayerMock leader = leaderOf("NULL", "obsidian");
        assertTrue(received(leader, "is now obsidian"));
        int[][] spots = {{10, 64, 10}, {-300, 70, 45}, {5000, 12, -77}, {0, 64, 0}, {123, 100, 456}};
        List<PlayerMock> walkers = new ArrayList<>();
        for (int i = 0; i < spots.length; i++) {
            PlayerMock walker = server.addPlayer("Walker" + i);
            walkers.add(walker);
            stepOn(walker, put(Material.OBSIDIAN, spots[i][0], spots[i][1], spots[i][2]));
        }
        assertTrue(walkers.stream().allMatch(w -> isMemberOf(w, "NULL")));
        assertEquals(walkers.size() + 1, plugin.squads().squadByName("NULL").orElseThrow().size());
    }

    @Test
    void theJoinMessageIsShortAndComesOnce() {
        leaderOf("NULL", "obsidian");
        PlayerMock walker = server.addPlayer("Walker");
        Block obsidian = put(Material.OBSIDIAN, 3, 64, 3);
        stepOn(walker, obsidian);
        assertEquals(1, messages(walker).stream().filter(m -> m.contains("You joined squad NULL")).count());
        stepOn(walker, obsidian);
        stepOn(walker, put(Material.OBSIDIAN, 4, 64, 3));
        assertTrue(messages(walker).isEmpty(), "no repeats once a member");
    }

    @Test
    void blocksPlacedAfterTheSquadWasConfiguredWorkToo() {
        leaderOf("NULL", "obsidian");
        PlayerMock early = server.addPlayer("Early");
        stepOn(early, put(Material.OBSIDIAN, 1, 64, 1));
        assertTrue(isMemberOf(early, "NULL"));
        PlayerMock late = server.addPlayer("Late");
        Block fresh = put(Material.OBSIDIAN, 9000, 64, 9000);
        stepOn(late, fresh);
        assertTrue(isMemberOf(late, "NULL"));
    }

    @Test
    void otherMaterialsDoNothingAndSoDoesAirUnderTheFeet() {
        leaderOf("NULL", "obsidian");
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, put(Material.STONE, 5, 64, 5));
        stepOn(walker, put(Material.CRYING_OBSIDIAN, 6, 64, 5));
        stepOn(walker, put(Material.GOLD_BLOCK, 7, 64, 5));
        assertFalse(isMemberOf(walker, "NULL"));
    }

    @Test
    void anySolidBlockCanBeTheJoiningBlock() {
        for (String material : new String[]{"gold_block", "minecraft:diamond_block", "stone", "oak_planks", "glass", "bedrock"}) {
            PlayerMock leader = leaderWithSquad("L" + material.hashCode(), "S" + Math.abs(material.hashCode()));
            run(leader, "squad", "block", "set", material);
            assertFalse(received(leader, "not a"), material);
            assertTrue(plugin.squads().profile("S" + Math.abs(material.hashCode())).orElseThrow().blockMaterial() != null, material);
        }
    }

    @Test
    void notSolidOrUnknownMaterialsAreRefusedWithAReason() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL");
        for (String bad : new String[]{"water", "air", "torch", "banana", "diamond_sword"}) {
            run(leader, "squad", "block", "set", bad);
            List<String> out = messages(leader);
            assertTrue(out.stream().anyMatch(m -> m.contains("not a") || m.contains("is not")), bad + " -> " + out);
        }
        assertNull(plugin.squads().profile("NULL").orElseThrow().blockMaterial());
    }

    @Test
    void settingWithoutAnArgumentUsesTheBlockUnderTheLeader() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL");
        Block under = put(Material.EMERALD_BLOCK, 20, 64, 20);
        leader.teleport(standingOn(under));
        run(leader, "squad", "block", "set");
        assertEquals(Material.EMERALD_BLOCK, plugin.squads().profile("NULL").orElseThrow().blockMaterial());
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, put(Material.EMERALD_BLOCK, -40, 80, 12));
        assertTrue(isMemberOf(walker, "NULL"));

        run(leader, "squad", "block", "set", "here");
        assertEquals(Material.EMERALD_BLOCK, plugin.squads().profile("NULL").orElseThrow().blockMaterial());
    }

    @Test
    void standingInTheAirWithNothingSolidUnderIsRefused() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL");
        leader.teleport(new Location(world, 50.5, 120, 50.5));
        run(leader, "squad", "block", "set");
        assertTrue(received(leader, "not a solid block"));
        assertNull(plugin.squads().profile("NULL").orElseThrow().blockMaterial());
    }

    @Test
    void landingOnTheBlockWithoutLeavingTheBlockColumnStillCounts() {
        leaderOf("NULL", "obsidian");
        PlayerMock walker = server.addPlayer("Jumper");
        Block obsidian = put(Material.OBSIDIAN, 8, 64, 8);
        Location air = new Location(world, 8.5, 65.4, 8.5);
        Location ground = new Location(world, 8.5, 65.0, 8.5);
        PlayerMoveEvent event = new PlayerMoveEvent(walker, air, ground);
        assertFalse(event.hasChangedBlock());
        server.getPluginManager().callEvent(event);
        assertTrue(isMemberOf(walker, "NULL"));
        assertEquals(Material.OBSIDIAN, obsidian.getType());
    }

    // ------------------------------------------------------------------ several squads, conflicts, worlds

    @Test
    void severalSquadsEachJoinByTheirOwnMaterial() {
        leaderOf("AAA", "obsidian");
        leaderOf("BBB", "gold_block");
        leaderOf("CCC", "emerald_block");
        PlayerMock a = server.addPlayer("A");
        PlayerMock b = server.addPlayer("B");
        PlayerMock c = server.addPlayer("C");
        stepOn(a, put(Material.OBSIDIAN, 1, 64, 1));
        stepOn(b, put(Material.GOLD_BLOCK, 2, 64, 2));
        stepOn(c, put(Material.EMERALD_BLOCK, 3, 64, 3));
        assertTrue(isMemberOf(a, "AAA"));
        assertTrue(isMemberOf(b, "BBB"));
        assertTrue(isMemberOf(c, "CCC"));
    }

    @Test
    void twoSquadsCannotUseTheSameMaterialInTheSameWorld() {
        leaderOf("AAA", "obsidian");
        PlayerMock other = leaderWithSquad("Other", "BBB");
        run(other, "squad", "block", "set", "obsidian");
        assertTrue(received(other, "already uses obsidian"));
        assertNull(plugin.squads().profile("BBB").orElseThrow().blockMaterial());
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, put(Material.OBSIDIAN, 1, 64, 1));
        assertTrue(isMemberOf(walker, "AAA"));
    }

    @Test
    void aSquadCanChangeItsMaterialAndTheOldOneStopsWorking() {
        PlayerMock leader = leaderOf("NULL", "obsidian");
        run(leader, "squad", "block", "set", "gold_block");
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, put(Material.OBSIDIAN, 1, 64, 1));
        assertFalse(isMemberOf(walker, "NULL"));
        stepOn(walker, put(Material.GOLD_BLOCK, 2, 64, 2));
        assertTrue(isMemberOf(walker, "NULL"));
        run(leader, "squad", "block", "set", "obsidian");
        PlayerMock other = leaderWithSquad("Other", "BBB");
        run(other, "squad", "block", "set", "gold_block");
        assertEquals(Material.GOLD_BLOCK, plugin.squads().profile("BBB").orElseThrow().blockMaterial(),
                "the freed material can be taken by another squad");
    }

    @Test
    void theRuleOnlyAppliesInTheWorldWhereItWasSetByDefault() {
        leaderOf("NULL", "obsidian");
        World other = server.addSimpleWorld("other");
        PlayerMock walker = server.addPlayer("Walker");
        Block there = other.getBlockAt(5, 64, 5);
        there.setType(Material.OBSIDIAN);
        stepOn(walker, there);
        assertFalse(isMemberOf(walker, "NULL"));
        stepOn(walker, put(Material.OBSIDIAN, 5, 64, 5));
        assertTrue(isMemberOf(walker, "NULL"));
    }

    @Test
    void theAllWorldsSettingMakesTheMaterialWorkEverywhere() {
        setConfig("blocks.all-worlds", true);
        leaderOf("NULL", "obsidian");
        World other = server.addSimpleWorld("other");
        PlayerMock walker = server.addPlayer("Walker");
        Block there = other.getBlockAt(5, 64, 5);
        there.setType(Material.OBSIDIAN);
        stepOn(walker, there);
        assertTrue(isMemberOf(walker, "NULL"));
    }

    @Test
    void aWorldSpecificSquadWinsOverTheAllWorldsFallback() {
        setConfig("blocks.all-worlds", true);
        leaderOf("AAA", "obsidian");
        World other = server.addSimpleWorld("other");
        PlayerMock boss = leaderWithSquad("OtherBoss", "BBB");
        boss.teleport(new Location(other, 0.5, 64, 0.5));
        run(boss, "squad", "block", "set", "obsidian");
        assertEquals(Material.OBSIDIAN, plugin.squads().profile("BBB").orElseThrow().blockMaterial(),
                "the same material in a different world is allowed");
        PlayerMock walker = server.addPlayer("Walker");
        Block there = other.getBlockAt(5, 64, 5);
        there.setType(Material.OBSIDIAN);
        stepOn(walker, there);
        assertTrue(isMemberOf(walker, "BBB"));
    }

    // ------------------------------------------------------------------ permissions and switches

    @Test
    void membersCannotConfigureButCanLook() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(1), "squad", "block", "set", "obsidian");
        assertTrue(received(squad.get(1), "Only the squad leader"));
        run(squad.get(0), "squad", "block", "set", "obsidian");
        run(squad.get(1), "squad", "block", "info");
        assertTrue(received(squad.get(1), "obsidian"));
    }

    @Test
    void adminsConfigureOtherSquadsAndNormalPlayersCannot() {
        leaderWithSquad("Boss", "NULL");
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        run(admin, "squad", "block", "set", "obsidian", "NULL");
        assertEquals(Material.OBSIDIAN, plugin.squads().profile("NULL").orElseThrow().blockMaterial());
        run(admin, "squad", "block", "info", "NULL");
        assertTrue(received(admin, "obsidian in world"));
        run(admin, "squad", "block", "disable", "NULL");
        assertFalse(plugin.squads().profile("NULL").orElseThrow().blockEnabled());
        run(admin, "squad", "block", "remove", "NULL");
        assertNull(plugin.squads().profile("NULL").orElseThrow().blockMaterial());

        PlayerMock other = leaderWithSquad("Other", "BBB");
        run(other, "squad", "block", "set", "gold_block", "NULL");
        assertTrue(received(other, "administrator"));
    }

    @Test
    void enableDisableAndRemove() {
        PlayerMock leader = leaderOf("NULL", "obsidian");
        PlayerMock walker = server.addPlayer("Walker");
        Block obsidian = put(Material.OBSIDIAN, 1, 64, 1);
        run(leader, "squad", "block", "disable");
        stepOn(walker, obsidian);
        assertFalse(isMemberOf(walker, "NULL"));
        run(leader, "squad", "block", "enable");
        stepOn(walker, obsidian);
        assertTrue(isMemberOf(walker, "NULL"));

        run(walker, "squad", "leave");
        run(leader, "squad", "block", "remove");
        assertTrue(received(leader, "Removed the joining block"));
        stepOn(walker, obsidian);
        assertFalse(isMemberOf(walker, "NULL"));
        run(leader, "squad", "block", "info");
        assertTrue(received(leader, "has no joining block"));
    }

    @Test
    void theWholeFeatureCanBeSwitchedOffInTheConfig() {
        leaderOf("NULL", "obsidian");
        setConfig("blocks.enabled", false);
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, put(Material.OBSIDIAN, 1, 64, 1));
        assertFalse(isMemberOf(walker, "NULL"));
    }

    @Test
    void thePadCommandPointsToTheNewOne() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL");
        run(leader, "squad", "pad", "set");
        assertTrue(received(leader, "replaced by joining blocks"));
        assertNull(plugin.squads().profile("NULL").orElseThrow().blockMaterial());
    }

    // ------------------------------------------------------------------ never forcing, never spamming, limits

    @Test
    void nobodyIsMovedOutOfAnExistingSquadAndTheNoticeIsNotSpammed() {
        leaderOf("NULL", "obsidian");
        PlayerMock busy = leaderWithSquad("Busy", "ALPHA");
        for (int i = 0; i < 20; i++) {
            stepOn(busy, put(Material.OBSIDIAN, i, 64, 0));
        }
        assertTrue(isMemberOf(busy, "ALPHA"));
        assertEquals(1, messages(busy).stream().filter(m -> m.contains("You are in squad ALPHA")).count());
    }

    @Test
    void aFullSquadTurnsPlayersAwayWithOneMessage() {
        setConfig("synchronization.max-squad-size", 3);
        leaderOf("NULL", "obsidian");
        List<PlayerMock> walkers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PlayerMock walker = server.addPlayer("W" + i);
            walkers.add(walker);
            stepOn(walker, put(Material.OBSIDIAN, i, 64, 0));
        }
        assertEquals(3, plugin.squads().squadByName("NULL").orElseThrow().size());
        assertTrue(received(walkers.get(4), "is full"));
    }

    @Test
    void leavingAndRejoining() {
        leaderOf("NULL", "obsidian");
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, put(Material.OBSIDIAN, 1, 64, 1));
        run(walker, "squad", "leave");
        assertFalse(isMemberOf(walker, "NULL"));
        messages(walker);
        stepOn(walker, put(Material.OBSIDIAN, 2, 64, 1));
        assertTrue(isMemberOf(walker, "NULL"));
        assertEquals(1, messages(walker).stream().filter(m -> m.contains("You joined")).count());
    }

    @Test
    void aSquadThatIsNotRunningExplainsInsteadOfJoining() {
        PlayerMock leader = leaderOf("NULL", "obsidian");
        run(leader, "squad", "leave");
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, put(Material.OBSIDIAN, 1, 64, 1));
        assertFalse(isMemberOf(walker, "NULL"));
        assertTrue(received(walker, "not running"));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 30, 100})
    void manyPlayersJoinOneAfterAnotherOnDifferentBlocks(int count) {
        leaderOf("NULL", "obsidian");
        List<PlayerMock> walkers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PlayerMock walker = server.addPlayer("W" + i);
            walkers.add(walker);
            stepOn(walker, put(Material.OBSIDIAN, i * 3, 64, (i % 7) * 5));
        }
        assertEquals(count + 1, plugin.squads().squadByName("NULL").orElseThrow().size());
        assertTrue(walkers.stream().allMatch(w -> isMemberOf(w, "NULL")));
    }

    @Test
    void manyPlayersArrivingAtOnceNeverExceedTheLimit() {
        setConfig("synchronization.max-squad-size", 20);
        leaderOf("NULL", "obsidian");
        Block obsidian = put(Material.OBSIDIAN, 10, 64, 10);
        for (int i = 0; i < 60; i++) {
            PlayerMock p = server.addPlayer("C" + i);
            server.getPluginManager().callEvent(new PlayerMoveEvent(p, standingOn(obsidian).add(0, 0, 3), standingOn(obsidian)));
        }
        assertEquals(20, plugin.squads().squadByName("NULL").orElseThrow().size());
    }

    @Test
    void movementIsCheapForHundredsOfPlayersWhetherOrNotAssignmentsExist() {
        leaderOf("NULL", "obsidian");
        List<PlayerMock> crowd = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            crowd.add(server.addPlayer("P" + i));
        }
        Location a = new Location(world, 100.5, 64, 100.5);
        Location sameBlock = new Location(world, 100.9, 64, 100.2);
        Location otherBlock = new Location(world, 101.5, 64, 100.5);
        long start = System.nanoTime();
        for (int round = 0; round < 1000; round++) {
            for (PlayerMock p : crowd) {
                server.getPluginManager().callEvent(new PlayerMoveEvent(p, a, round % 2 == 0 ? sameBlock : otherBlock));
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 15_000, "100000 move events took " + millis + " ms");
        assertTrue(crowd.stream().noneMatch(p -> plugin.squads().squadOf(p.getUniqueId()).isPresent()));
    }

    // ------------------------------------------------------------------ saving, restarts and the old coordinate pads

    @Test
    void theMaterialIsSavedAndFoundAgainAfterARestart() throws Exception {
        PlayerMock leader = leaderOf("NULL", "obsidian");
        run(leader, "squad", "speed", "5");
        plugin.flushSquads(true);
        Path file = plugin.getDataFolder().toPath().resolve("squads.yml");
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("material: obsidian"));
        assertFalse(yaml.contains("NULLBoss"), "no player names");

        SquadStore store = new SquadStore(file, plugin.getLogger());
        List<String> warnings = new ArrayList<>();
        SquadManager fresh = new SquadManager(System::currentTimeMillis);
        fresh.loadProfiles(store.load(warnings));
        fresh.reindexBlocks(profile -> world.getUID());

        assertEquals(List.of(), warnings);
        assertEquals(5, fresh.profile("null").orElseThrow().speed());
        assertTrue(fresh.blockFor(world.getUID(), Material.OBSIDIAN, false).isPresent());
        assertTrue(fresh.blockFor(world.getUID(), Material.STONE, false).isEmpty());
        assertTrue(fresh.squadByName("NULL").isEmpty(), "not running until the owner brings it back");
        assertEquals(SquadManager.Status.OK, fresh.create(leader.getUniqueId(), "null", false));
        SquadManager again = new SquadManager(System::currentTimeMillis);
        again.loadProfiles(store.load(new ArrayList<>()));
        assertEquals(SquadManager.Status.NAME_RESERVED, again.create(UUID.randomUUID(), "NULL", false));
    }

    @Test
    void aSavedWorldIsFoundByNameWhenItsIdChangedAndKeptDormantWhenNotLoaded() {
        SquadManager manager = new SquadManager(System::currentTimeMillis);
        manager.create(UUID.randomUUID(), "NULL", false);
        SquadProfile profile = manager.profile("NULL").orElseThrow();
        UUID oldId = UUID.randomUUID();
        manager.setBlock(profile, Material.OBSIDIAN, "world", oldId);
        assertTrue(manager.blockFor(oldId, Material.OBSIDIAN, false).isPresent());

        UUID newId = UUID.randomUUID();
        manager.reindexBlocks(p -> "world".equals(p.blockWorldName()) ? newId : null);
        assertTrue(manager.blockFor(oldId, Material.OBSIDIAN, false).isEmpty());
        assertTrue(manager.blockFor(newId, Material.OBSIDIAN, false).isPresent());

        manager.reindexBlocks(p -> null);
        assertFalse(manager.hasBlocks(newId, false));
        assertEquals(Material.OBSIDIAN, profile.blockMaterial(), "kept, just inactive");
    }

    @Test
    void anOldCoordinatePadIsRetiredNeverTriggersAndTheLeaderIsToldOnce() throws Exception {
        PlayerMock leader = leaderWithSquad("Boss", "NULL");
        run(leader, "squad", "leave");
        Path file = plugin.getDataFolder().toPath().resolve("squads.yml");
        Files.writeString(file, """
                version: 1
                squads:
                  null:
                    name: NULL
                    owner: %s
                    speed: 4
                    pad:
                      world: world
                      world-id: %s
                      x: 10
                      y: 64
                      z: 10
                      enabled: true
                """.formatted(leader.getUniqueId(), world.getUID()));
        SquadStore store = new SquadStore(file, plugin.getLogger());
        List<String> warnings = new ArrayList<>();
        plugin.squads().loadProfiles(store.load(warnings));
        plugin.joinBlocks().reindex();

        assertTrue(warnings.stream().anyMatch(w -> w.contains("retired")), warnings.toString());
        SquadProfile profile = plugin.squads().profile("NULL").orElseThrow();
        assertEquals(4, profile.speed(), "other settings survive");
        assertNull(profile.blockMaterial());

        // The old coordinate block (stone at 10,64,10) does nothing, even with a live squad.
        run(leader, "squad", "create", "NULL");
        assertTrue(received(leader, "retired"));
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, put(Material.STONE, 10, 64, 10));
        assertFalse(isMemberOf(walker, "NULL"));

        plugin.flushSquads(true);
        assertFalse(Files.readString(file).contains("legacy-pad"), "told once, then forgotten");
        assertFalse(Files.readString(file).contains("pad:"));
        run(leader, "squad", "leave");
        run(leader, "squad", "create", "NULL");
        assertFalse(received(leader, "retired"));
    }

    @Test
    void tabCompletionOffersBlockActionsAndMaterials() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL");
        assertTrue(command.complete(leader, new String[]{"squad", "bl"}).contains("block"));
        assertFalse(command.complete(leader, new String[]{"squad", ""}).contains("pad"));
        assertEquals(List.of("set"), command.complete(leader, new String[]{"squad", "block", "se"}));
        assertTrue(command.complete(leader, new String[]{"squad", "block", "set", ""}).containsAll(List.of("here", "obsidian")));
        assertTrue(command.complete(leader, new String[]{"squad", "block", "set", "obsi"}).contains("obsidian"));
        assertFalse(command.complete(leader, new String[]{"squad", "block", "set", "wat"}).contains("water"));
    }
}
