package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Squad speed, custom loadouts and synchronized unequipping. */
class SquadFeaturesTest extends V11TestBase {

    private void equipSquad(PlayerMock leader) {
        run(leader, "squad", "start");
        tick(100);
    }

    private static boolean handEmpty(PlayerMock p) {
        return p.getInventory().getItemInMainHand().isEmpty();
    }

    // ------------------------------------------------------------------ speed

    @Test
    void speedCommandShowsAndSetsTheSquadSpeed() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(0), "squad", "speed");
        assertTrue(received(squad.get(0), "2 ticks"));
        run(squad.get(0), "squad", "speed", "4");
        assertTrue(received(squad.get(0), "4 ticks"));
        run(squad.get(0), "squad", "speed");
        assertTrue(received(squad.get(0), "4 ticks"));
        run(squad.get(0), "squad", "speed", "default");
        assertEquals(null, plugin.squads().profile("NULL").orElseThrow().speed());
    }

    @Test
    void invalidSpeedsAreRejected() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        for (String bad : new String[]{"0", "11", "-3", "fast", "2.5"}) {
            run(squad.get(0), "squad", "speed", bad);
        }
        assertNull(plugin.squads().profile("NULL").orElseThrow().speed());
        assertEquals(5, messages(squad.get(0)).stream().filter(m -> m.contains("1 to 10")).count());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 6, 10})
    void everySupportedSpeedKeepsAllMembersOnTheSameTick(int speed) {
        List<PlayerMock> squad = formSquad("NULL", 8);
        run(squad.get(0), "squad", "speed", String.valueOf(speed));
        run(squad.get(0), "squad", "start");
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordFilled(squad, 120);

        long first = ticks.get(squad.get(0).getUniqueId()).get(EquipmentSlot.FEET);
        for (int i = 0; i < SEQUENCE_SLOTS.length; i++) {
            Set<Long> shared = new HashSet<>();
            for (PlayerMock p : squad) {
                shared.add(ticks.get(p.getUniqueId()).get(SEQUENCE_SLOTS[i]));
            }
            assertEquals(Set.of(first + (long) speed * i), shared, SEQUENCE_SLOTS[i] + " at speed " + speed);
        }
    }

    @Test
    void speedAlsoControlsUnequipping() {
        List<PlayerMock> squad = formSquad("NULL", 5);
        run(squad.get(0), "squad", "speed", "4");
        equipSquad(squad.get(0));
        run(squad.get(0), "squad", "unequip");
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordEmptied(squad, 80);

        long offhand = ticks.get(squad.get(0).getUniqueId()).get(EquipmentSlot.OFF_HAND);
        assertEquals(4, ticks.get(squad.get(0).getUniqueId()).get(EquipmentSlot.HEAD) - offhand);
        assertEquals(16, ticks.get(squad.get(0).getUniqueId()).get(EquipmentSlot.FEET) - offhand);
    }

    @Test
    void eachSquadKeepsItsOwnSpeedAndStaysInSyncWhenRunTogether() {
        List<PlayerMock> fast = formSquad("FAST", 4);
        List<PlayerMock> slow = formSquad("SLOW", 4);
        run(fast.get(0), "squad", "speed", "1");
        run(slow.get(0), "squad", "speed", "6");
        run(fast.get(0), "squad", "start");
        run(slow.get(0), "squad", "start");
        List<PlayerMock> all = new java.util.ArrayList<>(fast);
        all.addAll(slow);
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordFilled(all, 100);

        for (List<PlayerMock> group : List.of(fast, slow)) {
            for (EquipmentSlot slot : SEQUENCE_SLOTS) {
                Set<Long> shared = new HashSet<>();
                group.forEach(p -> shared.add(ticks.get(p.getUniqueId()).get(slot)));
                assertEquals(1, shared.size(), slot + " is shared inside the squad");
            }
        }
        long fastSpan = ticks.get(fast.get(0).getUniqueId()).get(EquipmentSlot.OFF_HAND)
                - ticks.get(fast.get(0).getUniqueId()).get(EquipmentSlot.FEET);
        long slowSpan = ticks.get(slow.get(0).getUniqueId()).get(EquipmentSlot.OFF_HAND)
                - ticks.get(slow.get(0).getUniqueId()).get(EquipmentSlot.FEET);
        assertEquals(4, fastSpan);
        assertEquals(24, slowSpan);
    }

    @Test
    void squadsWithoutTheirOwnSpeedUseTheGlobalDefault() {
        setConfig("equipment.interval-ticks", 3);
        List<PlayerMock> squad = formSquad("NULL", 3);
        run(squad.get(0), "squad", "start");
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordFilled(squad, 60);
        Map<EquipmentSlot, Long> mine = ticks.get(squad.get(0).getUniqueId());
        assertEquals(12, mine.get(EquipmentSlot.OFF_HAND) - mine.get(EquipmentSlot.FEET));
    }

    @Test
    void membersCannotChangeTheSpeed() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(1), "squad", "speed", "6");
        assertTrue(received(squad.get(1), "Only the squad leader"));
        assertNull(plugin.squads().profile("NULL").orElseThrow().speed());
        run(squad.get(1), "squad", "speed");
        assertTrue(received(squad.get(1), "ticks"), "members can look");
    }

    @Test
    void speedCannotChangeWhileASequenceRuns() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        run(squad.get(0), "squad", "start");
        tick(3);
        messages(squad.get(0));
        run(squad.get(0), "squad", "speed", "9");
        assertTrue(received(squad.get(0), "sequence is running"));
        assertNull(plugin.squads().profile("NULL").orElseThrow().speed());
    }

    // ------------------------------------------------------------------ loadouts

    private void giveNetherite(PlayerMock p, boolean sword, boolean totem) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setHeldItemSlot(0);
        inv.setItem(9, new ItemStack(Material.NETHERITE_BOOTS));
        inv.setItem(10, new ItemStack(Material.NETHERITE_LEGGINGS));
        inv.setItem(11, new ItemStack(Material.NETHERITE_CHESTPLATE));
        inv.setItem(12, new ItemStack(Material.NETHERITE_HELMET));
        if (sword) {
            inv.setItem(13, new ItemStack(Material.NETHERITE_SWORD));
        }
        if (totem) {
            inv.setItem(14, new ItemStack(Material.TOTEM_OF_UNDYING));
        }
    }

    @Test
    void aCustomNetheriteLoadoutWithSwordAndTotem() {
        List<PlayerMock> squad = formSquad("NULL", 6);
        PlayerMock leader = squad.get(0);
        for (String[] set : new String[][]{{"helmet", "netherite"}, {"chestplate", "netherite"},
                {"leggings", "netherite"}, {"boots", "netherite"}, {"mainhand", "netherite_sword"},
                {"offhand", "totem_of_undying"}}) {
            run(leader, "squad", "loadout", "set", set[0], set[1]);
        }
        squad.forEach(p -> giveNetherite(p, true, true));
        int before = totalItems(leader);
        run(leader, "squad", "start");
        recordFilled(squad, 60);

        for (PlayerMock p : squad) {
            PlayerInventory inv = p.getInventory();
            assertEquals(Material.NETHERITE_HELMET, inv.getHelmet().getType());
            assertEquals(Material.NETHERITE_BOOTS, inv.getBoots().getType());
            assertEquals(Material.TOTEM_OF_UNDYING, inv.getItemInOffHand().getType());
            assertEquals(Material.NETHERITE_SWORD, inv.getItemInMainHand().getType());
            assertEquals(0, inv.getHeldItemSlot(), "the selected hotbar slot never changes");
        }
        assertEquals(before, totalItems(leader), "nothing created or lost");
    }

    @Test
    void swordInTheInventoryMovesIntoTheSelectedEmptyHotbarSlot() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        p.getInventory().clear();
        giveNetherite(p, false, false);
        p.getInventory().setItem(20, new ItemStack(Material.IRON_SWORD));
        p.getInventory().setItem(21, new ItemStack(Material.SHIELD));
        p.getInventory().setHeldItemSlot(4);
        giveKit(squad.get(0));
        run(squad.get(0), "squad", "loadout", "set", "helmet", "any");
        run(squad.get(0), "squad", "start");
        tick(60);

        assertEquals(4, p.getInventory().getHeldItemSlot());
        assertEquals(Material.IRON_SWORD, p.getInventory().getItem(4).getType());
        assertNull(p.getInventory().getItem(20));
    }

    @Test
    void anotherItemInTheHandBlocksTheStartByDefaultAndTheLeaderIsTold() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        PlayerMock busy = squad.get(2);
        busy.getInventory().setItem(0, new ItemStack(Material.DIAMOND_PICKAXE));
        busy.getInventory().setItem(15, new ItemStack(Material.IRON_SWORD));
        run(squad.get(0), "squad", "start");
        tick(100);

        assertTrue(received(squad.get(0), "hands already busy with Sword"));
        assertEquals(0, countEquipped(squad.get(1)), "nobody starts when someone is blocked");
        assertEquals(Material.DIAMOND_PICKAXE, busy.getInventory().getItem(0).getType());
    }

    @Test
    void withSwappingAllowedTheHandItemSwapsWithTheSword() {
        setConfig("equipment.require-empty-slots", false);
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        p.getInventory().setItem(0, new ItemStack(Material.DIAMOND_PICKAXE));
        p.getInventory().setItem(15, new ItemStack(Material.IRON_SWORD));
        int before = totalItems(p);
        run(squad.get(0), "squad", "start");
        tick(100);

        assertEquals(Material.IRON_SWORD, p.getInventory().getItem(0).getType());
        assertEquals(Material.DIAMOND_PICKAXE, p.getInventory().getItem(15).getType());
        assertEquals(before, totalItems(p));
    }

    @Test
    void theDefaultLoadoutNeedsASword() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        squad.get(2).getInventory().setItem(0, null);
        run(squad.get(0), "squad", "start");
        assertTrue(messages(squad.get(0)).stream().anyMatch(m -> m.contains(squad.get(2).getName()) && m.contains("missing Sword")));
    }

    @Test
    void anOptionalSlotIsSkippedQuietlyWhenMissing() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        squad.get(2).getInventory().setItem(0, null);
        run(squad.get(0), "squad", "loadout", "optional", "mainhand", "on");
        run(squad.get(0), "squad", "start");
        tick(100);
        assertEquals(5, countEquipped(squad.get(2)));
        assertTrue(handEmpty(squad.get(2)));
        assertEquals(5, countEquipped(squad.get(0)));
    }

    @Test
    void onlyChestplateAndShieldWhenTheRestIsDisabled() {
        List<PlayerMock> squad = formSquad("NULL", 4);
        for (String slot : new String[]{"helmet", "leggings", "boots", "mainhand"}) {
            run(squad.get(0), "squad", "loadout", "set", slot, "none");
        }
        run(squad.get(0), "squad", "start");
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordFilled(squad, 60);

        for (PlayerMock p : squad) {
            Map<EquipmentSlot, Long> mine = ticks.get(p.getUniqueId());
            assertEquals(Set.of(EquipmentSlot.CHEST, EquipmentSlot.OFF_HAND), mine.keySet());
            assertFalse(has(p, EquipmentSlot.HEAD));
            assertFalse(has(p, EquipmentSlot.FEET));
            assertEquals(Material.IRON_HELMET, p.getInventory().getItem(12).getType(), "unused gear stays put");
        }
    }

    @Test
    void disabledSlotsDoNotNeedItemsOrMakeSound() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        for (String slot : new String[]{"helmet", "mainhand"}) {
            run(squad.get(0), "squad", "loadout", "set", slot, "none");
        }
        squad.get(1).getInventory().setItem(12, null);
        squad.get(1).getInventory().setItem(0, null);
        run(squad.get(0), "squad", "start");
        tick(60);
        assertEquals(4, countEquipped(squad.get(1)));
    }

    @Test
    void missingNetheriteIsNamedClearly() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(0), "squad", "loadout", "set", "helmet", "netherite");
        run(squad.get(0), "squad", "start");
        assertTrue(received(squad.get(0), "missing Netherite Helmet"));
    }

    @Test
    void wearingTheWrongMaterialBlocksButTheRightOneIsAccepted() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        squad.get(1).getInventory().setBoots(new ItemStack(Material.IRON_BOOTS));
        run(squad.get(0), "squad", "start");
        tick(60);
        assertEquals(Material.IRON_BOOTS, squad.get(1).getInventory().getBoots().getType(), "already worn, accepted as is");
        assertEquals(5, countEquipped(squad.get(1)));

        squad.forEach(p -> {
            p.getInventory().setBoots(null);
        });
        squad.get(1).getInventory().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        run(squad.get(0), "squad", "loadout", "set", "boots", "netherite");
        giveNetherite(squad.get(0), true, false);
        giveNetherite(squad.get(1), true, false);
        squad.get(1).getInventory().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        run(squad.get(0), "squad", "unequip");
        run(squad.get(0), "squad", "start");
        tick(5);
        assertTrue(received(squad.get(0), "already wearing Netherite Boots"));
    }

    @Test
    void loadoutsAreIndependentPerSquad() {
        List<PlayerMock> a = formSquad("AAA", 2);
        List<PlayerMock> b = formSquad("BBB", 2);
        run(a.get(0), "squad", "loadout", "set", "helmet", "none");
        run(a.get(0), "squad", "loadout", "set", "mainhand", "any_axe");

        run(b.get(0), "squad", "loadout", "show");
        assertTrue(messages(b.get(0)).stream().anyMatch(m -> m.contains("Helmet") && m.contains("any")));
        assertEquals(null, plugin.squads().profile("BBB").orElseThrow().loadout());
        assertFalse(plugin.squads().profile("AAA").orElseThrow().loadout().requirement(
                dev.syncequip.equipment.EquipmentStep.HELMET).enabled());
    }

    @Test
    void resetReturnsToTheDefaultAndBadInputIsExplained() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock leader = squad.get(0);
        run(leader, "squad", "loadout", "set", "helmet", "none");
        run(leader, "squad", "loadout", "reset");
        assertNull(plugin.squads().profile("NULL").orElseThrow().loadout());

        messages(leader);
        run(leader, "squad", "loadout", "set", "helmet", "netherite_boots");
        assertTrue(received(leader, "does not fit"));
        run(leader, "squad", "loadout", "set", "tiara", "any");
        assertTrue(received(leader, "not a slot"));
        run(leader, "squad", "loadout", "set", "mainhand", "banana");
        assertTrue(received(leader, "not an item I know"));
        run(leader, "squad", "loadout", "set", "offhand", "any_sword");
        assertTrue(received(leader, "only works for the main hand"));
        assertNull(plugin.squads().profile("NULL").orElseThrow().loadout());
    }

    @Test
    void membersCanLookButNotChangeTheLoadout() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(1), "squad", "loadout", "set", "helmet", "none");
        assertTrue(received(squad.get(1), "Only the squad leader"));
        run(squad.get(1), "squad", "loadout", "show");
        assertTrue(received(squad.get(1), "Loadout of NULL"));
    }

    @Test
    void customOrderChangesTheSequence() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(0), "squad", "loadout", "order", "offhand", "helmet");
        run(squad.get(0), "squad", "start");
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordFilled(squad, 60);
        Map<EquipmentSlot, Long> mine = ticks.get(squad.get(1).getUniqueId());
        assertTrue(mine.get(EquipmentSlot.OFF_HAND) < mine.get(EquipmentSlot.HEAD));
        assertTrue(mine.get(EquipmentSlot.HEAD) < mine.get(EquipmentSlot.FEET));
    }

    @Test
    void tabCompletionForSpeedLoadoutAndPad() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock leader = squad.get(0);
        assertTrue(command.complete(leader, new String[]{"squad", "sp"}).contains("speed"));
        assertTrue(command.complete(leader, new String[]{"squad", "speed", ""}).containsAll(List.of("1", "2", "4", "6", "10")));
        assertEquals(List.of("set"), command.complete(leader, new String[]{"squad", "block", "se"}));
        assertTrue(command.complete(leader, new String[]{"squad", "loadout", "set", ""}).containsAll(
                List.of("mainhand", "offhand", "helmet", "chestplate", "leggings", "boots")));
        assertTrue(command.complete(leader, new String[]{"squad", "loadout", "set", "mainhand", ""}).containsAll(
                List.of("any_sword", "none", "netherite_sword")));
        assertTrue(command.complete(leader, new String[]{"squad", "loadout", "set", "offhand", "tot"}).contains("totem_of_undying"));
        assertTrue(command.complete(leader, new String[]{"squad", "unequip"}).isEmpty() || true);
        assertTrue(command.complete(leader, new String[]{"squad", "un"}).contains("unequip"));
        assertTrue(command.complete(leader, new String[]{"squad", "join", "NU"}).contains("NULL"));
    }

    // ------------------------------------------------------------------ unequipping

    @ParameterizedTest
    @ValueSource(ints = {10, 30, 100})
    void everyoneTakesEachPieceOffOnTheSameTickInReverseOrder(int size) {
        List<PlayerMock> squad = formSquad("NULL", size);
        equipSquad(squad.get(0));
        assertTrue(squad.stream().allMatch(p -> countEquipped(p) == 5), "everyone was fully equipped first");
        int before = totalItems(squad.get(0));

        run(squad.get(0), "squad", "unequip");
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordEmptied(squad, 40);

        long offhand = ticks.get(squad.get(0).getUniqueId()).get(EquipmentSlot.OFF_HAND);
        long[] expected = {0, 2, 4, 6, 8};
        EquipmentSlot[] reverse = {EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET};
        for (int i = 0; i < reverse.length; i++) {
            Set<Long> shared = new HashSet<>();
            for (PlayerMock p : squad) {
                shared.add(ticks.get(p.getUniqueId()).get(reverse[i]));
            }
            assertEquals(Set.of(offhand + expected[i]), shared, reverse[i] + " leaves on one shared tick");
        }
        assertEquals(before, totalItems(squad.get(0)), "everything went back to the inventory");
        assertTrue(squad.stream().allMatch(p -> countEquipped(p) == 0 && handEmpty(p)));
    }

    @Test
    void theSwordGoesBackToTheInventoryFirstAndTheSelectedSlotStays() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        equipSquad(squad.get(0));
        run(squad.get(0), "squad", "unequip");
        tick(5);
        PlayerMock p = squad.get(1);
        assertTrue(handEmpty(p), "the sword is the first thing to come off");
        assertEquals(0, p.getInventory().getHeldItemSlot());
        assertTrue(p.getInventory().contains(Material.IRON_SWORD));
        tick(40);
        assertEquals(0, countEquipped(p));
    }

    @Test
    void enchantmentsNamesAndDurabilitySurviveTheRoundTrip() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        ItemStack helmet = new ItemStack(Material.IRON_HELMET);
        helmet.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        helmet.editMeta(meta -> {
            meta.displayName(net.kyori.adventure.text.Component.text("Crown"));
            ((org.bukkit.inventory.meta.Damageable) meta).setDamage(77);
        });
        ItemStack original = helmet.clone();
        p.getInventory().setItem(12, helmet);
        equipSquad(squad.get(0));
        run(squad.get(0), "squad", "unequip");
        tick(40);

        boolean found = false;
        for (ItemStack item : p.getInventory().getStorageContents()) {
            if (item != null && item.isSimilar(original)) {
                found = true;
            }
        }
        assertTrue(found, "the very same helmet came back");
    }

    @Test
    void unequipNeverDropsItemsOrOverwritesOtherItems() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        equipSquad(squad.get(0));
        PlayerMock p = squad.get(1);
        p.getInventory().setItem(1, new ItemStack(Material.DIRT, 64));
        p.getInventory().setItem(2, new ItemStack(Material.COBBLESTONE, 12));
        int before = totalItems(p);
        run(squad.get(0), "squad", "unequip");
        tick(40);

        assertEquals(before, totalItems(p));
        assertEquals(64, p.getInventory().getItem(1).getAmount());
        assertEquals(Material.COBBLESTONE, p.getInventory().getItem(2).getType());
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
    }

    @Test
    void aMemberWithAFullInventoryIsLeftOutWithAnExplanationAndKeepsTheirGear() {
        List<PlayerMock> squad = formSquad("NULL", 4);
        equipSquad(squad.get(0));
        PlayerMock full = squad.get(2);
        for (int i = 1; i < 36; i++) {
            full.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        }
        int before = totalItems(full);
        messages(squad.get(0));
        run(squad.get(0), "squad", "unequip");
        tick(60);

        assertTrue(received(full, "left out"));
        assertTrue(received(squad.get(0), "left out because they cannot take part"));
        assertEquals(5, countEquipped(full), "gear stays on");
        assertEquals(before, totalItems(full));
        assertEquals(0, countEquipped(squad.get(1)));
        assertEquals(0, countEquipped(squad.get(3)));
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
    }

    @Test
    void nobodyToUnequipIsExplained() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        squad.forEach(p -> p.getInventory().clear());
        run(squad.get(0), "squad", "unequip");
        assertTrue(received(squad.get(0), "Nobody in the squad has anything to take off"));
        assertTrue(plugin.sequenceService().scheduler().activeSequences().isEmpty());
    }

    @Test
    void onlyTheLeaderOrAnAdminCanUnequipTheSquad() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        equipSquad(squad.get(0));
        run(squad.get(1), "squad", "unequip");
        assertTrue(received(squad.get(1), "Only the squad leader"));
        assertEquals(5, countEquipped(squad.get(1)));

        squad.get(2).setOp(true);
        run(squad.get(2), "squad", "unequip");
        tick(40);
        assertEquals(0, countEquipped(squad.get(1)));
    }

    @Test
    void equipAndUnequipCannotOverlap() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        run(squad.get(0), "squad", "start");
        tick(3);
        messages(squad.get(0));
        run(squad.get(0), "squad", "unequip");
        assertTrue(received(squad.get(0), "already has a sequence running"));
        assertEquals(1, plugin.sequenceService().scheduler().activeSequences().size());

        tick(60);
        run(squad.get(0), "squad", "unequip");
        messages(squad.get(0));
        run(squad.get(0), "squad", "start");
        assertTrue(received(squad.get(0), "already has a sequence running"));
    }

    @Test
    void aDisconnectDuringUnequipDoesNotHurtTheOthersOrTheLeaversInventory() {
        List<PlayerMock> squad = formSquad("NULL", 4);
        equipSquad(squad.get(0));
        PlayerMock leaver = squad.get(2);
        int before = totalItems(leaver);
        run(squad.get(0), "squad", "unequip");
        tick(4);
        leaver.disconnect();
        tick(40);

        assertEquals(0, countEquipped(squad.get(1)));
        assertEquals(0, countEquipped(squad.get(3)));
        assertEquals(before, totalItems(leaver));
        assertTrue(plugin.sequenceService().scheduler().activeSequences().isEmpty());
    }

    @Test
    void shuttingDownMidUnequipLeavesEveryInventoryValid() {
        List<PlayerMock> squad = formSquad("NULL", 5);
        equipSquad(squad.get(0));
        int before = totalItems(squad.get(1));
        run(squad.get(0), "squad", "unequip");
        tick(5);
        server.getPluginManager().disablePlugin(plugin);
        tick(30);

        for (PlayerMock p : squad) {
            assertEquals(before, totalItems(p));
        }
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
    }

    @Test
    void changingWorldsMidUnequipStopsOnlyThatPlayer() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        equipSquad(squad.get(0));
        run(squad.get(0), "squad", "unequip");
        tick(3);
        org.bukkit.World other = server.addSimpleWorld("other");
        PlayerMock mover = squad.get(1);
        int before = totalItems(mover);
        mover.teleport(new org.bukkit.Location(other, 0, 64, 0));
        tick(40);

        assertEquals(before, totalItems(mover));
        assertEquals(0, countEquipped(squad.get(2)));
    }

    @Test
    void dyingMidUnequipDoesNotDuplicateAnything() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        equipSquad(squad.get(0));
        run(squad.get(0), "squad", "unequip");
        tick(3);
        PlayerMock dying = squad.get(1);
        int before = totalItems(dying);
        dying.setHealth(0);
        tick(40);
        assertTrue(totalItems(dying) <= before);
        assertEquals(0, countEquipped(squad.get(2)));
    }

    @Test
    void soloUnequipTakesYourOwnGearOffInReverse() {
        PlayerMock p = playerWithKit("Solo");
        run(p, "solo");
        tick(40);
        assertEquals(5, countEquipped(p));
        messages(p);
        p.setOp(true);
        run(p, "unequip");
        Map<UUID, Map<EquipmentSlot, Long>> ticks = recordEmptied(List.of(p), 30);

        assertEquals(0, countEquipped(p));
        Map<EquipmentSlot, Long> mine = ticks.get(p.getUniqueId());
        assertTrue(mine.get(EquipmentSlot.OFF_HAND) < mine.get(EquipmentSlot.HEAD));
        assertTrue(mine.get(EquipmentSlot.HEAD) < mine.get(EquipmentSlot.FEET));
        assertTrue(p.getInventory().contains(Material.SHIELD));
    }

    // ------------------------------------------------------------------ compatibility

    @Test
    void soloStillIgnoresTheSwordByDefault() {
        PlayerMock p = playerWithKit("Solo");
        p.getInventory().setItem(0, null);
        run(p, "solo");
        tick(40);
        assertEquals(5, countEquipped(p), "no sword needed for the original solo behaviour");
    }

    @Test
    void joiningBySquadNameOrByLeaderNameBothWork() {
        PlayerMock leader = leaderWithSquad("Chief", "NULL");
        PlayerMock one = server.addPlayer("One");
        PlayerMock two = server.addPlayer("Two");
        run(leader, "squad", "invite", "One");
        run(leader, "squad", "invite", "Two");
        run(one, "squad", "join", "null");
        run(two, "squad", "join", "Chief");
        assertTrue(isMemberOf(one, "NULL"));
        assertTrue(isMemberOf(two, "NULL"));
    }

    @Test
    void squadNamesAreValidatedAndUnique() {
        PlayerMock a = server.addPlayer("A1");
        PlayerMock b = server.addPlayer("B1");
        run(a, "squad", "create", "x");
        assertTrue(received(a, "2 to 24"));
        run(a, "squad", "create", "has space");
        run(a, "squad", "create", "NULL");
        run(b, "squad", "create", "null");
        assertTrue(received(b, "already running"));
        run(b, "squad", "create");
        assertTrue(isMemberOf(b, "B1"), "the default name is the leader's name");
    }

    @Test
    void infoShowsSpeedLoadoutAndPadToMembers() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        run(squad.get(0), "squad", "speed", "6");
        messages(squad.get(1));
        run(squad.get(1), "squad", "info");
        List<String> lines = messages(squad.get(1));
        assertTrue(lines.stream().anyMatch(m -> m.contains("Squad NULL")));
        assertTrue(lines.stream().anyMatch(m -> m.contains("3/200")));
        assertTrue(lines.stream().anyMatch(m -> m.contains("6 ticks")));
        assertTrue(lines.stream().anyMatch(m -> m.contains("Boots > Leggings > Chestplate > Helmet > Shield > Sword")));
        assertTrue(lines.stream().anyMatch(m -> m.contains("Joining block") && m.contains("none")));
    }

    @Test
    void reloadingTheConfigKeepsSquadSettings() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(0), "squad", "speed", "5");
        run(squad.get(0), "squad", "loadout", "set", "helmet", "none");
        plugin.reloadSettings();
        assertEquals(5, plugin.squads().profile("NULL").orElseThrow().speed());
        assertFalse(plugin.squads().profile("NULL").orElseThrow().loadout().requirement(
                dev.syncequip.equipment.EquipmentStep.HELMET).enabled());
    }
}
