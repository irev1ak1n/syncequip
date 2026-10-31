package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** End-to-end solo sequences on a mocked server, driven by the real scheduler task. */
class SoloSequenceTest extends PluginTestBase {

    /** Ticks the server one tick at a time and records the tick on which each slot was filled. */
    private Map<EquipmentSlot, Long> recordEquipTicks(PlayerMock player, int ticks) {
        Map<EquipmentSlot, Long> result = new EnumMap<>(EquipmentSlot.class);
        for (int i = 0; i < ticks; i++) {
            server.getScheduler().performOneTick();
            for (EquipmentSlot slot : SEQUENCE_SLOTS) {
                if (has(player, slot)) {
                    result.putIfAbsent(slot, now());
                }
            }
        }
        return result;
    }

    @Test
    void stepsLandAtOffsetsZeroTwoFourSixEight() {
        PlayerMock player = playerWithKit("Steve");
        long commandTick = now();

        run(player, "solo");
        Map<EquipmentSlot, Long> ticks = recordEquipTicks(player, 15);

        assertEquals(5, ticks.size(), "all five pieces equipped");
        long boots = ticks.get(EquipmentSlot.FEET);
        assertTrue(boots > commandTick, "nothing is equipped instantly by the command");
        List<Long> offsets = new ArrayList<>();
        for (EquipmentSlot slot : SEQUENCE_SLOTS) {
            offsets.add(ticks.get(slot) - boots);
        }
        assertEquals(List.of(0L, 2L, 4L, 6L, 8L), offsets);
        assertEquals(Material.IRON_SWORD, player.getInventory().getItemInMainHand().getType());
    }

    @Test
    void piecesAppearOneAtATime() {
        PlayerMock player = playerWithKit("Steve");
        run(player, "solo");

        List<Integer> equippedPerTick = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            server.getScheduler().performOneTick();
            equippedPerTick.add(countEquipped(player));
        }
        for (int i = 1; i < equippedPerTick.size(); i++) {
            assertTrue(equippedPerTick.get(i) - equippedPerTick.get(i - 1) <= 1, "at most one piece per tick");
        }
        assertEquals(5, equippedPerTick.getLast());
    }

    @Test
    void customIntervalIsHonoured() {
        setConfig("equipment.interval-ticks", 5);
        PlayerMock player = playerWithKit("Steve");
        run(player, "solo");
        Map<EquipmentSlot, Long> ticks = recordEquipTicks(player, 30);

        long boots = ticks.get(EquipmentSlot.FEET);
        assertEquals(20, ticks.get(EquipmentSlot.OFF_HAND) - boots);
    }

    @Test
    void customOrderIsHonoured() {
        setConfig("equipment.order", List.of("SHIELD", "HELMET", "BOOTS"));
        PlayerMock player = playerWithKit("Steve");
        run(player, "solo");
        Map<EquipmentSlot, Long> ticks = recordEquipTicks(player, 15);

        assertEquals(3, ticks.size());
        assertTrue(ticks.get(EquipmentSlot.OFF_HAND) < ticks.get(EquipmentSlot.HEAD));
        assertTrue(ticks.get(EquipmentSlot.HEAD) < ticks.get(EquipmentSlot.FEET));
        assertFalse(has(player, EquipmentSlot.CHEST));
    }

    @Test
    void missingItemAbortsBeforeAnythingMoves() {
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setItem(12, null); // no helmet
        run(player, "solo");
        tick(20);

        assertEquals(0, countEquipped(player));
        assertTrue(received(player, "missing Helmet"));
    }

    @Test
    void occupiedSlotAbortsByDefault() {
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        run(player, "solo");
        tick(20);

        assertEquals(1, countEquipped(player));
        assertTrue(received(player, "already wearing Boots"));
    }

    @Test
    void skipPolicyEquipsWhatIsAvailable() {
        setConfig("equipment.missing-item-policy", "SKIP");
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setItem(12, null);
        player.getInventory().setItem(13, null);
        run(player, "solo");
        tick(20);

        assertEquals(3, countEquipped(player));
        assertTrue(received(player, "3 of 5"));
    }

    @Test
    void cooldownBlocksImmediateRepeat() {
        PlayerMock player = playerWithKit("Steve");
        run(player, "solo");
        tick(15);
        messages(player);

        run(player, "solo");
        assertTrue(received(player, "on cooldown"));
    }

    @Test
    void cannotRunTwoSequencesAtOnce() {
        setConfig("solo.countdown-seconds", 2);
        PlayerMock player = playerWithKit("Steve");
        player.setOp(true); // bypass the cooldown so only the "busy" check applies
        run(player, "solo");
        tick(1);
        run(player, "solo");

        assertTrue(received(player, "already in a sequence"));
        assertEquals(1, plugin.sequenceService().scheduler().activeSequences().size());
    }

    @Test
    void itemRemovedMidSequenceInterruptsWithoutLosingAnything() {
        PlayerMock player = playerWithKit("Steve");
        int before = totalItems(player);
        run(player, "solo");
        tick(3); // boots + leggings on
        assertEquals(2, countEquipped(player));

        // Another plugin moves the helmet away before its step.
        ItemStack helmet = player.getInventory().getItem(12);
        player.getInventory().setItem(12, null);
        player.getInventory().setItem(35, new ItemStack(Material.DIRT)); // unrelated change
        tick(20);

        assertEquals(3, countEquipped(player), "boots, leggings and chestplate stay on");
        assertEquals(Material.SHIELD, player.getInventory().getItem(13).getType(), "shield untouched in inventory");
        assertTrue(received(player, "interrupted"));
        assertEquals(before - helmet.getAmount() + 1, totalItems(player));
    }

    @Test
    void deathMidSequenceStopsIt() {
        PlayerMock player = playerWithKit("Steve");
        run(player, "solo");
        tick(3);
        player.setHealth(0);
        tick(20);

        assertTrue(plugin.sequenceService().scheduler().activeSequences().isEmpty());
        assertTrue(countEquipped(player) <= 2);
    }

    @Test
    void disconnectMidSequenceStopsIt() {
        PlayerMock player = playerWithKit("Steve");
        int before = totalItems(player);
        run(player, "solo");
        tick(3);
        player.disconnect();
        tick(20);

        assertTrue(plugin.sequenceService().scheduler().activeSequences().isEmpty());
        assertEquals(2, countEquipped(player));
        assertEquals(before, totalItems(player));
    }

    @Test
    void disablingThePluginLeavesInventoryConsistent() {
        PlayerMock player = playerWithKit("Steve");
        int before = totalItems(player);
        run(player, "solo");
        tick(5); // boots, leggings, chestplate

        server.getPluginManager().disablePlugin(plugin);
        tick(20);

        assertEquals(3, countEquipped(player));
        assertEquals(before, totalItems(player));
        assertTrue(received(player, "shutting down"));
    }

    @Test
    void inventoryIsLockedOnlyWhileEquipping() {
        PlayerMock player = playerWithKit("Steve");
        run(player, "solo");
        tick(1);
        assertTrue(plugin.sequenceService().isLocked(player.getUniqueId()));
        tick(15);
        assertFalse(plugin.sequenceService().isLocked(player.getUniqueId()));
    }

    @Test
    void spectatorsCannotStart() {
        PlayerMock player = playerWithKit("Steve");
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        run(player, "solo");
        tick(15);
        assertEquals(0, countEquipped(player));
        assertTrue(received(player, "cannot change equipment"));
    }
}
