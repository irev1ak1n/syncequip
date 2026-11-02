package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class SquadSequenceTest extends PluginTestBase {

    private List<PlayerMock> formSquad(int size) {
        List<PlayerMock> squad = new ArrayList<>();
        PlayerMock leader = playerWithKit("Leader");
        squad.add(leader);
        run(leader, "squad", "create");
        for (int i = 1; i < size; i++) {
            PlayerMock member = playerWithKit("Soldier" + i);
            run(leader, "squad", "invite", member.getName());
            run(member, "squad", "join", leader.getName());
            squad.add(member);
        }
        squad.forEach(PluginTestBase::messages);
        return squad;
    }

    private Map<PlayerMock, Map<EquipmentSlot, Long>> recordEquipTicks(List<PlayerMock> players, int ticks) {
        Map<PlayerMock, Map<EquipmentSlot, Long>> result = new LinkedHashMap<>();
        players.forEach(p -> result.put(p, new EnumMap<>(EquipmentSlot.class)));
        for (int i = 0; i < ticks; i++) {
            server.getScheduler().performOneTick();
            for (PlayerMock player : players) {
                for (EquipmentSlot slot : SEQUENCE_SLOTS) {
                    if (has(player, slot)) {
                        result.get(player).putIfAbsent(slot, now());
                    }
                }
            }
        }
        return result;
    }

    @Test
    void tenPlayersEquipEachPieceOnTheSameServerTick() {
        List<PlayerMock> squad = formSquad(10);
        long startedAt = now();
        run(squad.getFirst(), "squad", "start");

        Map<PlayerMock, Map<EquipmentSlot, Long>> ticks = recordEquipTicks(squad, 80);

        Long boots = null;
        for (int i = 0; i < SEQUENCE_SLOTS.length; i++) {
            EquipmentSlot slot = SEQUENCE_SLOTS[i];
            Set<Long> slotTicks = new HashSet<>();
            for (PlayerMock player : squad) {
                slotTicks.add(ticks.get(player).get(slot));
            }
            assertEquals(1, slotTicks.size(), slot + " equipped on one shared tick by all 10 players");
            long tick = slotTicks.iterator().next();
            if (boots == null) {
                boots = tick;
            }
            assertEquals(2L * i, tick - boots, slot + " offset");
        }
        assertTrue(boots - startedAt >= 60, "3 second countdown precedes the first step");
    }

    @Test
    void debugLogShowsScheduledAndActualTickPerPlayerAndStep() {
        setConfig("debug.log-sequence-ticks", true);
        List<PlayerMock> squad = formSquad(4);
        List<String> log = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                log.add(logRecord.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(handler);
        try {
            run(squad.getFirst(), "squad", "start");
            tick(80);
        } finally {
            plugin.getLogger().removeHandler(handler);
        }

        Pattern line = Pattern.compile("step (\\d)/6 (\\w+)\\s+player=(\\S+)\\s+scheduled=(\\d+) actual=(\\d+) drift=0 result=(?:EQUIPPED|ALREADY)");
        Map<String, Set<String>> actualTicksPerStep = new LinkedHashMap<>();
        int lines = 0;
        for (String message : log) {
            Matcher m = line.matcher(message);
            if (m.find()) {
                lines++;
                assertEquals(m.group(4), m.group(5), "scheduled == actual at full TPS");
                actualTicksPerStep.computeIfAbsent(m.group(2), k -> new HashSet<>()).add(m.group(5));
            }
        }
        assertEquals(4 * 6, lines, "one debug line per player per step");
        assertEquals(6, actualTicksPerStep.size());
        actualTicksPerStep.forEach((step, ticks) -> assertEquals(1, ticks.size(), step + " on a single tick"));
    }

    @Test
    void thirtyPlayerSquadStaysInSync() {
        setConfig("synchronization.countdown-seconds", 0);
        List<PlayerMock> squad = formSquad(30);
        run(squad.getFirst(), "squad", "start");
        Map<PlayerMock, Map<EquipmentSlot, Long>> ticks = recordEquipTicks(squad, 20);
        for (EquipmentSlot slot : SEQUENCE_SLOTS) {
            assertEquals(1, squad.stream().map(p -> ticks.get(p).get(slot)).distinct().count(), slot.name());
        }
    }

    @Test
    void everyoneSeesTheSameCountdown() {
        List<PlayerMock> squad = formSquad(3);
        run(squad.getFirst(), "squad", "start");
        tick(1);
        for (PlayerMock player : squad) {
            assertTrue(received(player, "started the squad sequence"));
        }
        assertTrue(squad.stream().allMatch(p -> countEquipped(p) == 0), "nothing during countdown");
    }

    @Test
    void memberWithoutEquipmentIsLeftOutAndNamedWhileTheOthersEquip() {
        List<PlayerMock> squad = formSquad(3);
        squad.get(2).getInventory().setItem(10, null);
        run(squad.getFirst(), "squad", "start");
        tick(80);

        List<String> leaderMessages = messages(squad.getFirst());
        assertTrue(leaderMessages.stream().anyMatch(m -> m.contains("not ready")));
        assertTrue(leaderMessages.stream().anyMatch(m -> m.contains("Soldier2") && m.contains("missing Leggings")));
        assertEquals(5, countEquipped(squad.get(1)));
        assertEquals(0, countEquipped(squad.get(2)));
    }

    @Test
    void onlyTheLeaderCanStartOrCancel() {
        List<PlayerMock> squad = formSquad(2);
        PlayerMock member = squad.get(1);
        run(member, "squad", "start");
        assertTrue(received(member, "Only the squad leader"));
        run(member, "squad", "cancel");
        assertTrue(received(member, "Only the squad leader"));
        assertTrue(plugin.sequenceService().scheduler().activeSequences().isEmpty());
    }

    @Test
    void adminMemberMayStart() {
        List<PlayerMock> squad = formSquad(2);
        squad.get(1).setOp(true);
        run(squad.get(1), "squad", "start");
        assertEquals(1, plugin.sequenceService().scheduler().activeSequences().size());
    }

    @Test
    void cancelDuringCountdownEquipsNothing() {
        List<PlayerMock> squad = formSquad(4);
        run(squad.getFirst(), "squad", "start");
        tick(30);
        run(squad.getFirst(), "squad", "cancel");
        tick(80);

        assertTrue(squad.stream().allMatch(p -> countEquipped(p) == 0));
        assertTrue(received(squad.get(2), "cancelled before anything was equipped"));
    }

    @Test
    void cannotStartTwice() {
        List<PlayerMock> squad = formSquad(2);
        run(squad.getFirst(), "squad", "start");
        messages(squad.getFirst());
        run(squad.getFirst(), "squad", "start");
        assertTrue(received(squad.getFirst(), "already has a sequence running"));
    }

    @Test
    void memberLeavingDuringCountdownDoesNotStopTheOthers() {
        List<PlayerMock> squad = formSquad(3);
        run(squad.getFirst(), "squad", "start");
        tick(20);
        squad.get(1).disconnect();
        tick(80);

        assertEquals(5, countEquipped(squad.get(0)));
        assertEquals(0, countEquipped(squad.get(1)));
        assertEquals(5, countEquipped(squad.get(2)));
        assertTrue(received(squad.get(2), "Soldier1 left the squad"));
    }

    @Test
    void inventoryChangedDuringCountdownIsRevalidatedBeforeTheFirstStep() {
        List<PlayerMock> squad = formSquad(2);
        run(squad.getFirst(), "squad", "start");
        tick(30);
        squad.get(1).getInventory().setItem(13, null);
        tick(80);

        assertEquals(5, countEquipped(squad.get(0)));
        assertEquals(0, countEquipped(squad.get(1)), "removed before anything moved");
        assertTrue(received(squad.get(1), "before anything was equipped"));
    }

    @Test
    void leaderDisconnectingHandsOverLeadership() {
        List<PlayerMock> squad = formSquad(3);
        squad.getFirst().disconnect();

        PlayerMock newLeader = squad.get(1);
        assertTrue(received(newLeader, "Soldier1 is now the squad leader"));
        assertTrue(plugin.squads().squadOf(newLeader.getUniqueId()).orElseThrow().isLeader(newLeader.getUniqueId()));
        run(newLeader, "squad", "start");
        assertEquals(1, plugin.sequenceService().scheduler().activeSequences().size());
    }

    @Test
    void twoSquadsCanRunConcurrently() {
        setConfig("synchronization.countdown-seconds", 0);
        PlayerMock alpha = playerWithKit("Alpha");
        PlayerMock alpha2 = playerWithKit("Alpha2");
        PlayerMock bravo = playerWithKit("Bravo");
        PlayerMock bravo2 = playerWithKit("Bravo2");
        run(alpha, "squad", "create");
        run(alpha, "squad", "invite", "Alpha2");
        run(alpha2, "squad", "join", "Alpha");
        run(bravo, "squad", "create");
        run(bravo, "squad", "invite", "Bravo2");
        run(bravo2, "squad", "join", "Bravo");

        run(alpha, "squad", "start");
        tick(3);
        run(bravo, "squad", "start");
        assertEquals(2, plugin.sequenceService().scheduler().activeSequences().size());
        Map<PlayerMock, Map<EquipmentSlot, Long>> ticks = recordEquipTicks(List.of(alpha, alpha2, bravo, bravo2), 20);

        for (EquipmentSlot slot : SEQUENCE_SLOTS) {
            assertEquals(ticks.get(alpha).get(slot), ticks.get(alpha2).get(slot));
            assertEquals(ticks.get(bravo).get(slot), ticks.get(bravo2).get(slot));
        }
        assertEquals(3, ticks.get(bravo).get(EquipmentSlot.HEAD) - ticks.get(alpha).get(EquipmentSlot.HEAD));
        assertFalse(ticks.get(alpha).get(EquipmentSlot.OFF_HAND).equals(ticks.get(bravo).get(EquipmentSlot.OFF_HAND)));
        assertEquals(5, countEquipped(bravo2));
    }
}
