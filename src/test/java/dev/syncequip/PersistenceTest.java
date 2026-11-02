package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.squad.Squad;
import dev.syncequip.squad.SquadManager;
import dev.syncequip.squad.SquadStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Squad membership is saved by unique id and is separate from whether a sequence is running. */
class PersistenceTest extends V11TestBase {
    private Block obsidian(int x, int z) {
        Block block = world.getBlockAt(x, 64, z);
        block.setType(Material.OBSIDIAN);
        return block;
    }

    private SquadManager reload(List<String> warnings) {
        plugin.flushSquads(true);
        Path file = plugin.getDataFolder().toPath().resolve("squads.yml");
        SquadStore store = new SquadStore(file, plugin.getLogger());
        SquadManager fresh = new SquadManager(System::currentTimeMillis);
        fresh.loadProfiles(store.load(warnings));
        fresh.restoreSquads(warnings);
        return fresh;
    }

    // ------------------------------------------------------------------ messages

    @Test
    void steppingOnTheBlockJoinsOnceAndNeverNagsAfterwards() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL1");
        run(leader, "squad", "block", "set", "obsidian");
        PlayerMock walker = server.addPlayer("Walker");
        messages(walker);

        for (int i = 0; i < 12; i++) {
            stepOn(walker, obsidian(i * 3, 5));
        }
        List<String> heard = messages(walker);
        assertTrue(isMemberOf(walker, "NULL1"));
        assertEquals(1, heard.stream().filter(m -> m.contains("NULL1")).count(), heard.toString());
        assertEquals(1, heard.size(), "nothing but the one join message: " + heard);
    }

    @Test
    void aSquadWithNoSequenceRunningNeverProducesAWarning() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL1");
        run(leader, "squad", "block", "set", "obsidian");
        List<PlayerMock> walkers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            PlayerMock w = server.addPlayer("W" + i);
            walkers.add(w);
            stepOn(w, obsidian(i * 2, 9));
            stepOn(w, obsidian(i * 2, 11));
        }
        tick(400);
        assertEquals(21, plugin.squads().squadByName("NULL1").orElseThrow().size());
        for (PlayerMock w : walkers) {
            assertTrue(messages(w).stream().noneMatch(m -> m.contains("not running") || m.contains("Ask its leader")));
        }
    }

    @Test
    void membersWhoAreAlreadyInTheSquadHearNothingWhenTheyStepOnTheBlock() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL1");
        run(leader, "squad", "block", "set", "obsidian");
        messages(leader);
        stepOn(leader, obsidian(0, 20));
        stepOn(leader, obsidian(4, 20));
        assertEquals(List.of(), messages(leader));
    }

    @Test
    void aMemberOfAnotherSquadIsNeverMovedAndIsToldAtMostOnceInAWhile() {
        PlayerMock leader = leaderWithSquad("Boss", "NULL1");
        run(leader, "squad", "block", "set", "obsidian");
        PlayerMock other = leaderWithSquad("Other", "OTHER");
        messages(other);
        for (int i = 0; i < 8; i++) {
            stepOn(other, obsidian(i * 3, 30));
        }
        assertTrue(isMemberOf(other, "OTHER"));
        assertEquals(1, messages(other).stream().filter(m -> m.contains("NULL1")).count());
    }

    // ------------------------------------------------------------------ reconnecting

    @Test
    void disconnectingKeepsEveryoneInTheSquadAndTheLeaderKeepsLeading() {
        List<PlayerMock> squad = formSquad("NULL1", 4);
        UUID leaderId = squad.get(0).getUniqueId();
        UUID memberId = squad.get(2).getUniqueId();
        squad.get(0).disconnect();
        squad.get(2).disconnect();
        tick(40);

        Squad kept = plugin.squads().squadOf(leaderId).orElseThrow();
        assertTrue(kept.isLeader(leaderId));
        assertTrue(plugin.squads().squadOf(memberId).isPresent());
        assertEquals(4, kept.size());
        assertEquals(1, plugin.squads().squads().size());
    }

    @Test
    void anOnlineLeaderCanStartWhileOtherMembersAreOfflineWithoutAnyComplaint() {
        List<PlayerMock> squad = formSquad("NULL1", 4);
        squad.get(3).disconnect();
        messages(squad.get(0));
        run(squad.get(0), "squad", "start");
        tick(120);
        assertFalse(received(squad.get(0), "not ready"));
        assertFalse(received(squad.get(0), "offline"));
        assertEquals(5, countEquipped(squad.get(1)));
    }

    @Test
    void leavingOnPurposeStillRemovesYouAndSurvivesARestart() {
        List<PlayerMock> squad = formSquad("NULL1", 3);
        run(squad.get(2), "squad", "leave");
        List<String> warnings = new ArrayList<>();
        SquadManager fresh = reload(warnings);
        assertFalse(fresh.squadOf(squad.get(2).getUniqueId()).isPresent());
        assertEquals(2, fresh.squadByName("NULL1").orElseThrow().size());
    }

    // ------------------------------------------------------------------ restarts

    @Test
    void aRestartBringsBackOwnerMembersSettingsAndBlock() throws Exception {
        List<PlayerMock> squad = formSquad("NULL1", 3);
        PlayerMock leader = squad.get(0);
        run(leader, "squad", "speed", "4");
        run(leader, "squad", "block", "set", "obsidian");
        run(leader, "squad", "loadout", "set", "mainhand", "any_axe");

        List<String> warnings = new ArrayList<>();
        SquadManager fresh = reload(warnings);
        fresh.reindexBlocks(profile -> world.getUID());

        assertEquals(List.of(), warnings);
        Squad back = fresh.squadByName("NULL1").orElseThrow();
        assertEquals(leader.getUniqueId(), back.leader());
        assertEquals(List.copyOf(plugin.squads().squadByName("NULL1").orElseThrow().members()),
                List.copyOf(back.members()), "same members in the same order");
        assertEquals(4, back.profile().speed());
        assertEquals(Material.OBSIDIAN, back.profile().blockMaterial());
        assertTrue(back.profile().loadout() != null);
        assertTrue(fresh.blockFor(world.getUID(), Material.OBSIDIAN, false).isPresent());
        String yaml = Files.readString(plugin.getDataFolder().toPath().resolve("squads.yml"));
        assertFalse(yaml.contains("Soldier") || yaml.contains("NULL1L"), "ids only, no player names");
    }

    @Test
    void aSavedLeaderTransferIsKept() {
        List<PlayerMock> squad = formSquad("NULL1", 3);
        run(squad.get(0), "squad", "transfer", squad.get(1).getName());
        SquadManager fresh = reload(new ArrayList<>());
        assertEquals(squad.get(1).getUniqueId(), fresh.squadByName("NULL1").orElseThrow().leader());
    }

    @Test
    void anOldFileWithoutMembersBringsBackJustTheOwnerAsLeader() throws Exception {
        UUID owner = UUID.randomUUID();
        Path file = plugin.getDataFolder().toPath().resolve("squads.yml");
        Files.writeString(file, """
                version: 1
                squads:
                  null1:
                    name: NULL1
                    owner: %s
                    speed: 3
                """.formatted(owner));
        List<String> warnings = new ArrayList<>();
        SquadManager fresh = new SquadManager(System::currentTimeMillis);
        fresh.loadProfiles(new SquadStore(file, plugin.getLogger()).load(warnings));
        fresh.restoreSquads(warnings);

        Squad back = fresh.squadByName("NULL1").orElseThrow();
        assertEquals(owner, back.leader());
        assertEquals(1, back.size());
        assertEquals(3, back.profile().speed());
    }

    @Test
    void anOldPadOnlySquadDoesNotTakeTheOwnerAwayFromTheirRealSquad() throws Exception {
        UUID owner = UUID.randomUUID();
        Path file = plugin.getDataFolder().toPath().resolve("squads.yml");
        Files.writeString(file, """
                version: 1
                squads:
                  old:
                    name: Old
                    owner: %s
                    legacy-pad: world 1 64 1
                  real:
                    name: Real
                    owner: %s
                    block:
                      material: obsidian
                      world: world
                """.formatted(owner, owner));
        SquadManager fresh = new SquadManager(System::currentTimeMillis);
        List<String> warnings = new ArrayList<>();
        fresh.loadProfiles(new SquadStore(file, plugin.getLogger()).load(warnings));
        fresh.restoreSquads(warnings);
        assertEquals("Real", fresh.squadOf(owner).orElseThrow().name());
        assertTrue(fresh.squadByName("Old").isEmpty());
    }

    @Test
    void aPlayerListedInTwoSquadsIsNeverMovedFromTheFirst() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        Path file = plugin.getDataFolder().toPath().resolve("squads.yml");
        Files.writeString(file, """
                version: 1
                squads:
                  first:
                    name: First
                    owner: %s
                    members: [%s, %s]
                  second:
                    name: Second
                    owner: %s
                    members: [%s, %s]
                """.formatted(a, a, shared, b, b, shared));
        List<String> warnings = new ArrayList<>();
        SquadManager fresh = new SquadManager(System::currentTimeMillis);
        fresh.loadProfiles(new SquadStore(file, plugin.getLogger()).load(warnings));
        fresh.restoreSquads(warnings);

        assertEquals("First", fresh.squadOf(shared).orElseThrow().name());
        assertEquals(1, fresh.squadByName("Second").orElseThrow().size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("not moved")), warnings.toString());
    }

    @Test
    void disbandingRemovesTheSavedSquadForGood() {
        List<PlayerMock> squad = formSquad("NULL1", 2);
        run(squad.get(0), "squad", "disband");
        SquadManager fresh = reload(new ArrayList<>());
        assertTrue(fresh.squadByName("NULL1").isEmpty());
        assertTrue(fresh.profile("NULL1").isEmpty());
        assertTrue(fresh.squadOf(squad.get(1).getUniqueId()).isEmpty());
    }
}
