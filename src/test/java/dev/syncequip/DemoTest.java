package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.synchronization.SyncSequence;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Husk;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

class DemoTest extends PluginTestBase {

    private PlayerMock op;

    @BeforeEach
    void makeOp() {
        op = server.addPlayer("Director");
        op.setOp(true);
        // MockBukkit has no support for the spawn overload Paper uses, so use the simple one.
        plugin.demo().setSpawner((at, init) -> at.getWorld().spawn(at, Husk.class, h -> init.accept(lenient(h))));
        setConfig("demo.countdown-seconds", 0);
        messages(op);
    }

    /** Wraps a mock husk so setters MockBukkit has not implemented yet are ignored instead of failing. */
    private static Husk lenient(Husk real) {
        return (Husk) Proxy.newProxyInstance(Husk.class.getClassLoader(), new Class<?>[]{Husk.class}, (proxy, method, args) -> {
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof UnimplementedOperationException) {
                    Class<?> type = method.getReturnType();
                    return type == boolean.class ? Boolean.FALSE : type.isPrimitive() && type != void.class ? 0 : null;
                }
                throw e.getCause();
            }
        });
    }

    private List<Husk> husks() {
        return plugin.demo().liveMobs();
    }

    private static boolean wears(Husk husk, EquipmentSlot slot) {
        ItemStack item = husk.getEquipment().getItem(slot);
        return item != null && !item.isEmpty();
    }

    private static int worn(Husk husk) {
        int count = 0;
        for (EquipmentSlot slot : SEQUENCE_SLOTS) {
            if (wears(husk, slot)) {
                count++;
            }
        }
        return count;
    }

    /** Ticks one at a time and records the tick each mob first wore each slot. */
    private Map<UUID, Map<EquipmentSlot, Long>> recordTicks(int ticks) {
        Map<UUID, Map<EquipmentSlot, Long>> result = new LinkedHashMap<>();
        for (int i = 0; i < ticks; i++) {
            server.getScheduler().performOneTick();
            for (Husk husk : husks()) {
                for (EquipmentSlot slot : SEQUENCE_SLOTS) {
                    if (wears(husk, slot)) {
                        result.computeIfAbsent(husk.getUniqueId(), k -> new LinkedHashMap<>()).putIfAbsent(slot, now());
                    }
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ spawning

    @Test
    void spawnCreatesStationaryHarmlessHusksWithEmptySlots() {
        run(op, "demo", "spawn", "5");

        List<Husk> husks = husks();
        assertEquals(5, husks.size());
        assertTrue(received(op, "Spawned 5 demo husks"));
        for (Husk husk : husks) {
            assertFalse(husk.hasAI(), "AI disabled");
            assertTrue(husk.isInvulnerable(), "cannot be hurt");
            assertTrue(husk.isSilent());
            assertFalse(husk.isPersistent(), "never saved to the world");
            assertEquals(0, worn(husk), "starts with every slot empty");
            assertEquals(0f, husk.getEquipment().getHelmetDropChance());
            assertTrue(plugin.demo().isDemoMob(husk));
        }
    }

    @Test
    void spawnedMobsStandInOneStraightRowFacingTheSameWay() {
        op.teleport(new Location(op.getWorld(), 0, 64, 0, 0f, 0f));
        run(op, "demo", "spawn", "6");

        List<Husk> husks = husks();
        Set<Long> ys = new HashSet<>();
        Set<Float> yaws = new HashSet<>();
        for (Husk husk : husks) {
            ys.add(Math.round(husk.getLocation().getY() * 100));
            yaws.add(husk.getLocation().getYaw());
        }
        assertEquals(1, ys.size(), "same height");
        assertEquals(1, yaws.size(), "same facing");
        double gap = husks.get(1).getLocation().distance(husks.get(0).getLocation());
        for (int i = 1; i < husks.size(); i++) {
            assertEquals(gap, husks.get(i).getLocation().distance(husks.get(i - 1).getLocation()), 1e-6, "even spacing");
        }
        // Player faces +z (yaw 0), so the row sits in front of the player along the x axis.
        for (Husk husk : husks) {
            assertEquals(4.0, husk.getLocation().getZ(), 1e-6, "distance in front of the player");
        }
    }

    @Test
    void spawnRejectsCountsOutsideOneToTwenty() {
        run(op, "demo", "spawn", "0");
        run(op, "demo", "spawn", "21");
        run(op, "demo", "spawn", "lots");
        run(op, "demo", "spawn");
        assertEquals(0, husks().size());
        assertTrue(received(op, "from 1 to 20"));
    }

    @Test
    void peacefulDifficultyIsExplainedInsteadOfSpawningNothing() {
        op.getWorld().setDifficulty(org.bukkit.Difficulty.PEACEFUL);
        run(op, "demo", "spawn", "5");
        assertEquals(0, husks().size());
        assertTrue(received(op, "Peaceful"));
    }

    @Test
    void twentyMobsWork() {
        run(op, "demo", "spawn", "20");
        assertEquals(20, husks().size());
    }

    @Test
    void spawningAgainReplacesTheOldRow() {
        run(op, "demo", "spawn", "4");
        Set<UUID> first = new HashSet<>();
        husks().forEach(h -> first.add(h.getUniqueId()));
        run(op, "demo", "spawn", "3");

        assertEquals(3, husks().size());
        assertTrue(husks().stream().noneMatch(h -> first.contains(h.getUniqueId())));
        assertEquals(3, op.getWorld().getEntitiesByClass(Husk.class).size(), "old husks are gone");
    }

    // ------------------------------------------------------------------ permission

    @Test
    void demoCommandsNeedPermission() {
        PlayerMock player = server.addPlayer("Regular");
        run(player, "demo", "spawn", "3");
        run(player, "demo", "start");
        run(player, "demo", "clear");

        assertEquals(0, husks().size());
        assertEquals(3, messages(player).stream().filter(m -> m.contains("do not have permission")).count());
        assertEquals(List.of(), command.complete(player, new String[]{"demo", ""}));
        assertFalse(command.complete(player, new String[]{""}).contains("demo"));
    }

    @Test
    void permissionCanBeGrantedWithoutOp() {
        PlayerMock player = server.addPlayer("Helper");
        player.addAttachment(plugin, "syncequip.demo", true);
        run(player, "demo", "spawn", "2");
        assertEquals(2, husks().size());
    }

    // ------------------------------------------------------------------ the sequence

    @Test
    void everyMobEquipsEachPieceOnTheSameTickAtFourTickSpacing() {
        run(op, "demo", "spawn", "10");
        run(op, "demo", "start");
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordTicks(40);

        assertEquals(10, ticks.size());
        long boots = ticks.values().iterator().next().get(EquipmentSlot.FEET);
        for (int i = 0; i < SEQUENCE_SLOTS.length; i++) {
            EquipmentSlot slot = SEQUENCE_SLOTS[i];
            Set<Long> slotTicks = new HashSet<>();
            ticks.values().forEach(m -> slotTicks.add(m.get(slot)));
            assertEquals(1, slotTicks.size(), slot + " on one shared tick for all 10 mobs");
            assertEquals(4L * i, slotTicks.iterator().next() - boots, slot + " offset at 4 tick spacing");
        }
    }

    @Test
    void mobsWearNetheriteArmorAndAShieldInTheRightSlots() {
        run(op, "demo", "spawn", "3");
        run(op, "demo", "start");
        tick(40);

        for (Husk husk : husks()) {
            assertEquals(Material.NETHERITE_BOOTS, husk.getEquipment().getBoots().getType());
            assertEquals(Material.NETHERITE_LEGGINGS, husk.getEquipment().getLeggings().getType());
            assertEquals(Material.NETHERITE_CHESTPLATE, husk.getEquipment().getChestplate().getType());
            assertEquals(Material.NETHERITE_HELMET, husk.getEquipment().getHelmet().getType());
            assertEquals(Material.SHIELD, husk.getEquipment().getItemInOffHand().getType());
        }
    }

    @Test
    void piecesAppearOneAtATime() {
        run(op, "demo", "spawn", "4");
        run(op, "demo", "start");
        int previous = 0;
        for (int i = 0; i < 30; i++) {
            server.getScheduler().performOneTick();
            int now = worn(husks().getFirst());
            assertTrue(now - previous <= 1, "never two pieces in one tick");
            previous = now;
        }
        assertEquals(5, previous);
    }

    @Test
    void usesTheSharedSchedulerWithOneDemoSequence() {
        run(op, "demo", "spawn", "6");
        run(op, "demo", "start");
        tick(1);

        List<SyncSequence> active = plugin.sequenceService().scheduler().activeSequences();
        assertEquals(1, active.size());
        assertEquals(SyncSequence.Mode.DEMO, active.getFirst().mode());
        Set<UUID> mobIds = new HashSet<>();
        husks().forEach(h -> mobIds.add(h.getUniqueId()));
        assertEquals(mobIds, active.getFirst().participants());
    }

    @Test
    void speedChangesTheSpacingForTheNextRun() {
        run(op, "demo", "spawn", "3");
        run(op, "demo", "speed", "2");
        assertTrue(received(op, "2 ticks"));
        run(op, "demo", "start");
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordTicks(30);

        Map<EquipmentSlot, Long> first = ticks.values().iterator().next();
        assertEquals(8, first.get(EquipmentSlot.OFF_HAND) - first.get(EquipmentSlot.FEET));
    }

    @Test
    void invalidSpeedIsRejectedAndKeepsTheOldValue() {
        run(op, "demo", "speed", "0");
        run(op, "demo", "speed", "11");
        run(op, "demo", "speed", "fast");
        run(op, "demo", "speed");
        assertEquals(4, plugin.demo().interval());
        assertTrue(received(op, "1 to 10"));
    }

    @Test
    void defaultDemoIntervalIsFourWhileEngineDefaultStaysTwo() {
        assertEquals(4, plugin.settings().demo().intervalTicks());
        assertEquals(2, plugin.settings().intervalTicks());
    }

    @Test
    void countdownRunsBeforeTheFirstPiece() {
        setConfig("demo.countdown-seconds", 1);
        run(op, "demo", "spawn", "2");
        run(op, "demo", "start");
        tick(10);
        assertEquals(0, worn(husks().getFirst()), "nothing during the countdown");
        tick(30);
        assertEquals(5, worn(husks().getFirst()));
    }

    // ------------------------------------------------------------------ replay, stop, cleanup

    @Test
    void resetEmptiesTheMobsAndTheSequenceCanReplayWithoutRespawning() {
        run(op, "demo", "spawn", "4");
        Set<UUID> ids = new HashSet<>();
        husks().forEach(h -> ids.add(h.getUniqueId()));
        run(op, "demo", "start");
        tick(40);
        assertEquals(5, worn(husks().getFirst()));

        run(op, "demo", "reset");
        assertTrue(husks().stream().allMatch(h -> worn(h) == 0));
        assertTrue(received(op, "Removed the equipment from 4"));

        run(op, "demo", "start");
        tick(40);
        assertTrue(husks().stream().allMatch(h -> worn(h) == 5));
        Set<UUID> after = new HashSet<>();
        husks().forEach(h -> after.add(h.getUniqueId()));
        assertEquals(ids, after, "same mobs, no respawn");
    }

    @Test
    void startingAgainUndressesTheMobsFirst() {
        run(op, "demo", "spawn", "3");
        run(op, "demo", "start");
        tick(40);
        run(op, "demo", "start");
        assertEquals(0, worn(husks().getFirst()), "reset happened as soon as the new run was requested");
        tick(40);
        assertEquals(5, worn(husks().getFirst()));
    }

    @Test
    void stopMidwayLeavesEquippedPiecesAndCancelsTheRest() {
        run(op, "demo", "spawn", "3");
        run(op, "demo", "start");
        tick(10);
        int wornNow = worn(husks().getFirst());
        assertTrue(wornNow > 0 && wornNow < 5, "part way through, was " + wornNow);

        run(op, "demo", "stop");
        tick(40);

        assertEquals(wornNow, worn(husks().getFirst()));
        assertTrue(plugin.sequenceService().scheduler().activeSequences().isEmpty());
        assertFalse(plugin.demo().isRunning());
    }

    @Test
    void stopDuringCountdownEquipsNothing() {
        setConfig("demo.countdown-seconds", 3);
        run(op, "demo", "spawn", "3");
        run(op, "demo", "start");
        tick(20);
        run(op, "demo", "stop");
        tick(100);
        assertTrue(husks().stream().allMatch(h -> worn(h) == 0));
    }

    @Test
    void secondStartWhileRunningIsRefused() {
        setConfig("demo.countdown-seconds", 2);
        run(op, "demo", "spawn", "2");
        run(op, "demo", "start");
        messages(op);
        run(op, "demo", "start");
        assertTrue(received(op, "already going"));
        assertEquals(1, plugin.sequenceService().scheduler().activeSequences().size());
    }

    @Test
    void startWithoutMobsExplainsWhat_ToDo() {
        run(op, "demo", "start");
        assertTrue(received(op, "no demo mobs"));
    }

    @Test
    void repeatRunsTheSequenceSeveralTimes() {
        setConfig("demo.repeat-hold-seconds", 1);
        run(op, "demo", "spawn", "3");
        run(op, "demo", "repeat", "3");

        List<Integer> wornPerTick = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            server.getScheduler().performOneTick();
            wornPerTick.add(worn(husks().getFirst()));
        }
        int fullRuns = 0;
        int fullStreakStart = 0;
        for (int i = 1; i < wornPerTick.size(); i++) {
            if (wornPerTick.get(i - 1) < 5 && wornPerTick.get(i) == 5) {
                fullRuns++;
            }
            if (wornPerTick.get(i) == 0 && wornPerTick.get(i - 1) == 5) {
                fullStreakStart++;
            }
        }
        assertEquals(3, fullRuns, "fully dressed three times");
        assertEquals(2, fullStreakStart, "undressed between the runs");
        assertFalse(plugin.demo().isRunning());
    }

    @Test
    void repeatRejectsBadCounts() {
        run(op, "demo", "spawn", "2");
        run(op, "demo", "repeat", "0");
        run(op, "demo", "repeat", "x");
        run(op, "demo", "repeat");
        assertFalse(plugin.demo().isRunning());
        assertTrue(received(op, "Usage"));
    }

    @Test
    void clearRemovesEveryDemoMobAndStopsTheRun() {
        setConfig("demo.countdown-seconds", 2);
        run(op, "demo", "spawn", "5");
        run(op, "demo", "start");
        tick(5);
        run(op, "demo", "clear");

        assertEquals(0, op.getWorld().getEntitiesByClass(Husk.class).size());
        assertTrue(received(op, "Removed 5 demo mobs"));
        tick(100);
        assertTrue(plugin.sequenceService().scheduler().activeSequences().isEmpty());
    }

    @Test
    void mobKilledMidRunDoesNotBreakTheOthers() {
        run(op, "demo", "spawn", "4");
        run(op, "demo", "start");
        tick(6);
        husks().get(1).remove();
        tick(40);

        List<Husk> alive = husks();
        assertEquals(3, alive.size());
        assertTrue(alive.stream().allMatch(h -> worn(h) == 5));
    }

    @Test
    void leftoverTaggedMobsAreRemovedOnEnableAndReload() {
        run(op, "demo", "spawn", "3");
        List<Husk> original = husks();
        plugin.demo().clear();
        // Simulate mobs left behind by a crash: tagged, but nothing tracks them.
        Husk stray = op.getWorld().spawn(op.getLocation(), Husk.class);
        stray.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "demo"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        Husk innocent = op.getWorld().spawn(op.getLocation(), Husk.class);

        Set<UUID> removed = plugin.demo().removeTaggedMobs();

        assertEquals(Set.of(stray.getUniqueId()), removed);
        assertFalse(stray.isValid());
        assertTrue(innocent.isValid(), "ordinary husks are never touched");
        assertEquals(3, original.size());
    }

    @Test
    void disablingThePluginRemovesTheDemoMobs() {
        run(op, "demo", "spawn", "5");
        run(op, "demo", "start");
        tick(3);
        server.getPluginManager().disablePlugin(plugin);

        assertEquals(0, op.getWorld().getEntitiesByClass(Husk.class).size());
        assertTrue(plugin.sequenceService().scheduler().activeSequences().isEmpty());
    }

    // ------------------------------------------------------------------ isolation from players

    @Test
    void demoNeverTouchesPlayerInventoriesOrSquads() {
        giveKit(op);
        int before = totalItems(op);
        run(op, "demo", "spawn", "5");
        run(op, "demo", "start");
        tick(60);
        run(op, "demo", "reset");
        run(op, "demo", "clear");

        assertEquals(before, totalItems(op));
        assertEquals(0, countEquipped(op));
        assertTrue(op.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).isEmpty(), "no item drops");
    }

    @Test
    void soloAndSquadStillWorkAfterDemoUse() {
        setConfig("controls.cooldown-seconds", 0);
        run(op, "demo", "spawn", "3");
        run(op, "demo", "start");
        giveKit(op);
        run(op, "solo");
        tick(60);
        assertEquals(5, countEquipped(op));
        assertNotEquals(0, husks().size());
    }

    @Test
    void tabCompletionOffersDemoSubcommandsAndNumbers() {
        assertTrue(command.complete(op, new String[]{""}).contains("demo"));
        assertEquals(List.of("spawn", "speed", "start", "stop").stream().sorted().toList(),
                command.complete(op, new String[]{"demo", "s"}).stream().sorted().toList());
        assertEquals(List.of("20"), command.complete(op, new String[]{"demo", "spawn", "2"}).stream()
                .filter(s -> s.equals("20")).toList());
    }

    @Test
    void consoleCannotSpawnButCanClear() {
        command.handle(server.getConsoleSender(), new String[]{"demo", "spawn", "3"});
        assertEquals(0, husks().size());
        run(op, "demo", "spawn", "3");
        command.handle(server.getConsoleSender(), new String[]{"demo", "clear"});
        assertEquals(0, husks().size());
    }

    @Test
    void debugLogCoversDemoStepsPerMob() {
        setConfig("debug.log-sequence-ticks", true);
        List<String> log = new ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                log.add(r.getMessage());
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
            run(op, "demo", "spawn", "3");
            run(op, "demo", "start");
            tick(40);
        } finally {
            plugin.getLogger().removeHandler(handler);
        }
        long stepLines = log.stream().filter(l -> l.contains("step ") && l.contains("player=Demo")).count();
        assertEquals(3 * 5, stepLines);
        assertTrue(log.stream().anyMatch(l -> l.contains("(DEMO) scheduled")));
    }
}
