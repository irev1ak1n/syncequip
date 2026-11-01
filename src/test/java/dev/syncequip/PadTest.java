package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.squad.SquadProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PadTest extends V11TestBase {

    /** A leader of squad NULL who has registered a stone block as the joining block. */
    private record Setup(PlayerMock leader, Block pad) {
    }

    private Setup squadWithPad(String squad, int x, int z) {
        PlayerMock leader = leaderWithSquad(squad + "Boss", squad);
        Block pad = stone(x, 64, z);
        leader.teleport(standingOn(pad));
        run(leader, "squad", "pad", "set");
        return new Setup(leader, pad);
    }

    @Test
    void steppingOnTheBlockJoinsTheSquad() {
        Setup s = squadWithPad("NULL", 10, 10);
        assertTrue(received(s.leader(), "Joining block for NULL set"));
        PlayerMock walker = server.addPlayer("Walker");

        stepOn(walker, s.pad());

        assertTrue(isMemberOf(walker, "NULL"));
        assertTrue(received(walker, "You joined squad NULL"));
    }

    @Test
    void onlyTheExactBlockCountsNotTheMaterial() {
        Setup s = squadWithPad("NULL", 10, 10);
        Block lookalike = stone(20, 64, 20);
        Block neighbour = stone(11, 64, 10);
        PlayerMock walker = server.addPlayer("Walker");

        stepOn(walker, lookalike);
        stepOn(walker, neighbour);

        assertFalse(isMemberOf(walker, "NULL"));
        stepOn(walker, s.pad());
        assertTrue(isMemberOf(walker, "NULL"));
    }

    @Test
    void movingInsideTheSameBlockDoesNothing() {
        Setup s = squadWithPad("NULL", 10, 10);
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, s.pad());
        run(walker, "squad", "leave");
        messages(walker);

        Location a = standingOn(s.pad()).add(0.1, 0, 0);
        Location b = standingOn(s.pad()).add(0.4, 0, 0.3);
        PlayerMoveEvent event = new PlayerMoveEvent(walker, a, b);
        assertFalse(event.hasChangedBlock());
        server.getPluginManager().callEvent(event);

        assertFalse(isMemberOf(walker, "NULL"), "small movements and head turns are ignored");
    }

    @Test
    void padNeedsASolidBlock() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL");
        leader.teleport(new Location(world, 50.5, 100, 50.5));
        run(leader, "squad", "pad", "set");
        assertTrue(received(leader, "solid block"));
        assertTrue(plugin.squads().profile("NULL").orElseThrow().pad() == null);
    }

    @Test
    void membersCannotConfigureThePad() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        Block pad = stone(5, 64, 5);
        squad.get(1).teleport(standingOn(pad));
        run(squad.get(1), "squad", "pad", "set");

        assertTrue(received(squad.get(1), "Only the squad leader"));
        assertTrue(plugin.squads().profile("NULL").orElseThrow().pad() == null);
    }

    @Test
    void adminCanSetAndRemoveThePadOfAnotherSquad() {
        leaderWithSquad("Boss", "NULL");
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        Block pad = stone(7, 64, 7);
        admin.teleport(standingOn(pad));

        run(admin, "squad", "pad", "set", "NULL");
        assertTrue(plugin.squads().profile("NULL").orElseThrow().pad() != null);
        run(admin, "squad", "pad", "info", "NULL");
        assertTrue(received(admin, "world 7 64 7"));
        run(admin, "squad", "pad", "remove", "NULL");
        assertTrue(plugin.squads().profile("NULL").orElseThrow().pad() == null);
    }

    @Test
    void aNormalPlayerCannotTouchSomeoneElsesSquadByName() {
        leaderWithSquad("Boss", "NULL");
        PlayerMock other = leaderWithSquad("Other", "ALPHA");
        Block pad = stone(7, 64, 7);
        other.teleport(standingOn(pad));
        run(other, "squad", "pad", "set", "NULL");
        assertTrue(received(other, "administrator"));
        assertTrue(plugin.squads().profile("NULL").orElseThrow().pad() == null);
    }

    @Test
    void twoSquadsCannotShareABlock() {
        Setup first = squadWithPad("NULL", 10, 10);
        PlayerMock other = leaderWithSquad("Other", "ALPHA");
        other.teleport(standingOn(first.pad()));
        run(other, "squad", "pad", "set");

        assertTrue(received(other, "already uses that block"));
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, first.pad());
        assertTrue(isMemberOf(walker, "NULL"));
    }

    @Test
    void settingAgainMovesThePad() {
        Setup s = squadWithPad("NULL", 10, 10);
        Block other = stone(30, 64, 30);
        s.leader().teleport(standingOn(other));
        run(s.leader(), "squad", "pad", "set");

        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, s.pad());
        assertFalse(isMemberOf(walker, "NULL"), "the old block no longer works");
        stepOn(walker, other);
        assertTrue(isMemberOf(walker, "NULL"));
    }

    @Test
    void disableAndEnable() {
        Setup s = squadWithPad("NULL", 10, 10);
        run(s.leader(), "squad", "pad", "disable");
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, s.pad());
        assertFalse(isMemberOf(walker, "NULL"));

        run(s.leader(), "squad", "pad", "enable");
        stepOn(walker, s.pad());
        assertTrue(isMemberOf(walker, "NULL"));
    }

    @Test
    void anExistingSquadIsNeverReplacedAndTheNoticeIsNotSpammed() {
        Setup s = squadWithPad("NULL", 10, 10);
        PlayerMock busy = leaderWithSquad("Busy", "ALPHA");

        for (int i = 0; i < 20; i++) {
            stepOn(busy, s.pad());
        }

        assertTrue(isMemberOf(busy, "ALPHA"));
        assertEquals(1, messages(busy).stream().filter(m -> m.contains("You are in squad ALPHA")).count());
    }

    @Test
    void aFullSquadTurnsPlayersAwayWithOneMessage() {
        setConfig("synchronization.max-squad-size", 3);
        Setup s = squadWithPad("NULL", 10, 10);
        List<PlayerMock> walkers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PlayerMock walker = server.addPlayer("W" + i);
            walkers.add(walker);
            stepOn(walker, s.pad());
        }
        assertEquals(3, plugin.squads().squadByName("NULL").orElseThrow().size());
        assertTrue(received(walkers.get(4), "is full"));
    }

    @Test
    void leavingAndRejoining() {
        Setup s = squadWithPad("NULL", 10, 10);
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, s.pad());
        run(walker, "squad", "leave");
        assertFalse(isMemberOf(walker, "NULL"));
        messages(walker);

        stepOn(walker, s.pad());
        assertTrue(isMemberOf(walker, "NULL"));
        assertEquals(1, messages(walker).stream().filter(m -> m.contains("You joined squad NULL")).count());
    }

    @Test
    void standingOnThePadAgainDoesNotRepeatTheMessage() {
        Setup s = squadWithPad("NULL", 10, 10);
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, s.pad());
        messages(walker);
        stepOn(walker, s.pad());
        stepOn(walker, s.pad());
        assertTrue(messages(walker).isEmpty());
    }

    @Test
    void breakingThePadBlockRemovesTheRegistrationAndTellsTheLeader() {
        Setup s = squadWithPad("NULL", 10, 10);
        messages(s.leader());
        server.getPluginManager().callEvent(new BlockBreakEvent(s.pad(), s.leader()));

        assertTrue(plugin.squads().profile("NULL").orElseThrow().pad() == null);
        assertTrue(received(s.leader(), "was destroyed"));
    }

    @Test
    void aPadWhoseSquadStoppedRunningExplainsInsteadOfJoining() {
        Setup s = squadWithPad("NULL", 10, 10);
        PlayerMock walker = server.addPlayer("Walker");
        run(s.leader(), "squad", "leave");

        stepOn(walker, s.pad());

        assertFalse(isMemberOf(walker, "NULL"));
        assertTrue(received(walker, "not running"));
    }

    @Test
    void disbandingDeletesThePad() {
        Setup s = squadWithPad("NULL", 10, 10);
        run(s.leader(), "squad", "disband");
        assertTrue(plugin.squads().profile("NULL").isEmpty());
        PlayerMock walker = server.addPlayer("Walker");
        stepOn(walker, s.pad());
        assertTrue(plugin.squads().squadOf(walker.getUniqueId()).isEmpty());
    }

    @Test
    void severalSquadsEachHaveTheirOwnBlock() {
        Setup a = squadWithPad("NULL", 10, 10);
        Setup b = squadWithPad("ALPHA", 30, 30);
        PlayerMock one = server.addPlayer("One");
        PlayerMock two = server.addPlayer("Two");

        stepOn(one, a.pad());
        stepOn(two, b.pad());

        assertTrue(isMemberOf(one, "NULL"));
        assertTrue(isMemberOf(two, "ALPHA"));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 30, 100})
    void manyPlayersJoinOneAfterAnother(int count) {
        Setup s = squadWithPad("NULL", 10, 10);
        List<PlayerMock> walkers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PlayerMock walker = server.addPlayer("W" + i);
            walkers.add(walker);
            stepOn(walker, s.pad());
        }
        assertEquals(count + 1, plugin.squads().squadByName("NULL").orElseThrow().size());
        assertTrue(walkers.stream().allMatch(w -> isMemberOf(w, "NULL")));
        for (PlayerMock walker : walkers) {
            assertEquals(1, messages(walker).stream().filter(m -> m.contains("You joined")).count());
        }
    }

    @Test
    void manyPlayersArrivingAtOnceNeverExceedTheLimit() {
        setConfig("synchronization.max-squad-size", 20);
        Setup s = squadWithPad("NULL", 10, 10);
        List<PlayerMock> crowd = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            crowd.add(server.addPlayer("C" + i));
        }
        // All events are fired back to back, as when a crowd runs onto the block in the same tick.
        for (PlayerMock p : crowd) {
            Location from = standingOn(s.pad()).add(0, 0, 3);
            server.getPluginManager().callEvent(new PlayerMoveEvent(p, from, standingOn(s.pad())));
        }
        assertEquals(20, plugin.squads().squadByName("NULL").orElseThrow().size());
    }

    @Test
    void movementEventsAreCheapForHundredsOfPlayers() {
        squadWithPad("NULL", 10, 10);
        List<PlayerMock> crowd = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            crowd.add(server.addPlayer("P" + i));
        }
        Location a = new Location(world, 100.5, 64, 100.5);
        Location sameBlock = new Location(world, 100.9, 64, 100.2);
        Location otherBlock = new Location(world, 101.5, 64, 100.5);

        long start = System.nanoTime();
        int events = 0;
        for (int round = 0; round < 1000; round++) {
            for (PlayerMock p : crowd) {
                server.getPluginManager().callEvent(new PlayerMoveEvent(p, a, round % 2 == 0 ? sameBlock : otherBlock));
                events++;
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(100_000, events);
        assertTrue(millis < 15_000, "100000 move events took " + millis + " ms");
        assertTrue(crowd.stream().noneMatch(p -> plugin.squads().squadOf(p.getUniqueId()).isPresent()));
    }

    @Test
    void padsAreSavedAndComeBackWhenTheLeaderRecreatesTheSquad() {
        Setup s = squadWithPad("NULL", 10, 10);
        run(s.leader(), "squad", "speed", "5");
        run(s.leader(), "squad", "loadout", "set", "boots", "none");
        plugin.flushSquads(true);

        // A restart is simulated by building a fresh manager from the file, the way onEnable does.
        dev.syncequip.squad.SquadStore store = new dev.syncequip.squad.SquadStore(
                plugin.getDataFolder().toPath().resolve("squads.yml"), plugin.getLogger());
        List<String> warnings = new ArrayList<>();
        dev.syncequip.squad.SquadManager fresh = new dev.syncequip.squad.SquadManager(System::currentTimeMillis);
        fresh.loadProfiles(store.load(warnings));
        fresh.reindexPads(pad -> world.getUID());

        assertEquals(List.of(), warnings);
        SquadProfile profile = fresh.profile("null").orElseThrow();
        assertEquals(5, profile.speed());
        assertEquals("none", profile.loadout().requirement(dev.syncequip.equipment.EquipmentStep.BOOTS).token());
        assertEquals(s.leader().getUniqueId(), profile.owner());
        assertTrue(fresh.padAt(world.getUID(), 10, 64, 10).isPresent(), "the exact block is indexed again");
        assertTrue(fresh.padAt(world.getUID(), 11, 64, 10).isEmpty());
        assertTrue(fresh.squadByName("NULL").isEmpty(), "squads are not running until their leader brings them back");

        assertEquals(dev.syncequip.squad.SquadManager.Status.OK, fresh.create(s.leader().getUniqueId(), "null", false));
        assertEquals(5, fresh.squadByName("NULL").orElseThrow().effectiveSpeed(2));
    }

    @Test
    void aSavedPadFindsItsWorldAgainByNameWhenTheIdChanged() {
        dev.syncequip.squad.SquadManager manager = new dev.syncequip.squad.SquadManager(System::currentTimeMillis);
        UUID owner = UUID.randomUUID();
        manager.create(owner, "NULL", false);
        SquadProfile profile = manager.profile("NULL").orElseThrow();
        UUID oldId = UUID.randomUUID();
        manager.setPad(profile, new dev.syncequip.squad.Pad(oldId, "world", 1, 2, 3), oldId);
        assertTrue(manager.padAt(oldId, 1, 2, 3).isPresent());

        UUID newId = UUID.randomUUID();
        manager.reindexPads(pad -> pad.worldName().equals("world") ? newId : null);
        assertTrue(manager.padAt(oldId, 1, 2, 3).isEmpty());
        assertTrue(manager.padAt(newId, 1, 2, 3).isPresent());

        manager.reindexPads(pad -> null);
        assertFalse(manager.hasPads(newId), "a pad in an unloaded world is kept but inactive");
        assertTrue(profile.pad() != null);
    }

    @Test
    void someoneElseCannotTakeOverASavedSquadName() {
        Setup s = squadWithPad("NULL", 10, 10);
        run(s.leader(), "squad", "leave");
        PlayerMock thief = server.addPlayer("Thief");
        run(thief, "squad", "create", "NULL");
        assertTrue(received(thief, "belongs to another player"));
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        run(admin, "squad", "create", "NULL");
        assertTrue(isMemberOf(admin, "NULL"));
    }

    @Test
    void savedFileHoldsNoPlayerNamesOrMembers() throws Exception {
        Setup s = squadWithPad("NULL", 10, 10);
        List<PlayerMock> others = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PlayerMock p = server.addPlayer("Visitor" + i);
            stepOn(p, s.pad());
            others.add(p);
        }
        plugin.flushSquads(true);
        String yaml = java.nio.file.Files.readString(plugin.getDataFolder().toPath().resolve("squads.yml"));

        assertFalse(yaml.contains("Visitor"));
        assertFalse(yaml.contains("NULLBoss"));
        assertTrue(yaml.contains(s.leader().getUniqueId().toString()));
        assertTrue(yaml.contains("x: 10"));
        UUID unused = UUID.randomUUID();
        assertFalse(yaml.contains(unused.toString()));
        assertEquals(Material.STONE, s.pad().getType());
    }
}
