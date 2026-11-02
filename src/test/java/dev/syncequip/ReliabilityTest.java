package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Regression tests for item detection: where equipment may be found, and that checking and doing agree.
 * The first group is the reported bug: an armor piece held in a hand was reported as missing.
 */
class ReliabilityTest extends V11TestBase {
    private PlayerInventory bare(PlayerMock p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setHeldItemSlot(0);
        return inv;
    }

    private void fullIronKitExceptHelmet(PlayerInventory inv) {
        inv.setItem(9, new ItemStack(Material.IRON_BOOTS));
        inv.setItem(10, new ItemStack(Material.IRON_LEGGINGS));
        inv.setItem(11, new ItemStack(Material.IRON_CHESTPLATE));
        inv.setItem(13, new ItemStack(Material.SHIELD));
    }

    private void solo(PlayerMock p) {
        p.setOp(true);
        run(p, "solo");
        tick(60);
    }

    // ------------------------------------------------------------------ the reported bug

    @Test
    void aHelmetHeldInTheMainHandIsFoundAndWornBySolo() {
        PlayerMock p = server.addPlayer("Solo");
        PlayerInventory inv = bare(p);
        fullIronKitExceptHelmet(inv);
        inv.setItem(0, new ItemStack(Material.IRON_HELMET));
        int before = totalItems(p);

        solo(p);

        assertFalse(received(p, "Cannot start"));
        assertEquals(Material.IRON_HELMET, inv.getHelmet().getType());
        assertTrue(inv.getItemInMainHand().isEmpty(), "the helmet left the hand");
        assertEquals(5, countEquipped(p));
        assertEquals(before, totalItems(p));
    }

    @Test
    void aHelmetHeldInTheOffhandIsFoundAndWornBySolo() {
        PlayerMock p = server.addPlayer("Solo");
        PlayerInventory inv = bare(p);
        fullIronKitExceptHelmet(inv);
        inv.setItemInOffHand(new ItemStack(Material.IRON_HELMET));
        int before = totalItems(p);

        solo(p);

        assertEquals(Material.IRON_HELMET, inv.getHelmet().getType());
        assertEquals(Material.SHIELD, inv.getItemInOffHand().getType(), "the shield took the freed offhand");
        assertEquals(before, totalItems(p));
    }

    @Test
    void everyArmorPieceCanBeInTheHandOrAnywhereInTheInventory() {
        int[] spots = {0, 8, 20, 35, 1};
        Material[] pieces = {Material.NETHERITE_BOOTS, Material.DIAMOND_LEGGINGS, Material.GOLDEN_CHESTPLATE,
                Material.LEATHER_HELMET, Material.SHIELD};
        PlayerMock p = server.addPlayer("Solo");
        PlayerInventory inv = bare(p);
        for (int i = 0; i < pieces.length; i++) {
            inv.setItem(spots[i], new ItemStack(pieces[i]));
        }
        solo(p);
        assertEquals(5, countEquipped(p));
        assertEquals(Material.NETHERITE_BOOTS, inv.getBoots().getType());
        assertEquals(Material.LEATHER_HELMET, inv.getHelmet().getType());
    }

    @Test
    void aHeldHelmetAndASwordInTheInventoryWorkInASquad() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = bare(p);
        fullIronKitExceptHelmet(inv);
        inv.setItem(0, new ItemStack(Material.IRON_HELMET));
        inv.setItem(14, new ItemStack(Material.IRON_SWORD));
        int before = totalItems(p);

        run(squad.get(0), "squad", "start");
        tick(100);

        assertFalse(received(squad.get(0), "not ready"));
        assertEquals(Material.IRON_HELMET, inv.getHelmet().getType());
        assertEquals(Material.IRON_SWORD, inv.getItemInMainHand().getType(), "the sword took the hand the helmet left");
        assertEquals(before, totalItems(p));
        assertEquals(0, inv.getHeldItemSlot());
    }

    @Test
    void thePreCheckAndTheExecutionAgreeWhenTheHandsFreeUpDuringTheSequence() {
        // The hand holds a helmet and the offhand a torch. Nothing is "occupied" once the earlier steps have run.
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = bare(p);
        fullIronKitExceptHelmet(inv);
        inv.setItem(0, new ItemStack(Material.IRON_HELMET));
        inv.setItem(14, new ItemStack(Material.IRON_SWORD));
        inv.setItem(15, new ItemStack(Material.TORCH, 8));
        run(squad.get(0), "squad", "start");
        assertFalse(received(squad.get(0), "not ready"), "a helmet held in the hand is not a busy hand");
    }

    // ------------------------------------------------------------------ real conflicts are explained

    @Test
    void oneItemNeededByTwoSlotsIsReportedAsAConflictNotAsMissing() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock leader = squad.get(0);
        run(leader, "squad", "loadout", "set", "mainhand", "shield");
        PlayerMock p = squad.get(1);
        PlayerInventory inv = bare(p);
        fullIronKitExceptHelmet(inv);
        inv.setItem(12, new ItemStack(Material.IRON_HELMET));
        inv.setItem(13, null);
        inv.setItem(0, new ItemStack(Material.SHIELD));
        messages(leader);

        run(leader, "squad", "start");

        List<String> out = messages(leader);
        assertTrue(out.stream().anyMatch(m -> m.contains(p.getName()) && m.contains("needed for another slot")), out.toString());
        assertTrue(out.stream().noneMatch(m -> m.contains("missing Shield")), "the shield exists, it is not missing");
        assertEquals(Material.SHIELD, inv.getItemInMainHand().getType(), "nothing moved");
    }

    @Test
    void aTrulyMissingItemIsStillReportedMissing() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        p.getInventory().setItem(12, null);
        run(squad.get(0), "squad", "start");
        assertTrue(received(squad.get(0), "missing Helmet"));
    }

    // ------------------------------------------------------------------ hands, sword, axe, shield, totem

    @Test
    void axeAndTotemLoadoutMovesBothIntoTheHands() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(0), "squad", "loadout", "set", "mainhand", "any_axe");
        run(squad.get(0), "squad", "loadout", "set", "offhand", "totem_of_undying");
        for (PlayerMock p : squad) {
            PlayerInventory inv = bare(p);
            fullIronKitExceptHelmet(inv);
            inv.setItem(13, null);
            inv.setItem(12, new ItemStack(Material.IRON_HELMET));
            inv.setItem(30, new ItemStack(Material.DIAMOND_AXE));
            inv.setItem(31, new ItemStack(Material.TOTEM_OF_UNDYING));
        }
        run(squad.get(0), "squad", "start");
        tick(100);
        for (PlayerMock p : squad) {
            assertEquals(Material.DIAMOND_AXE, p.getInventory().getItemInMainHand().getType());
            assertEquals(Material.TOTEM_OF_UNDYING, p.getInventory().getItemInOffHand().getType());
            assertEquals(4, (int) java.util.stream.Stream.of(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST,
                    EquipmentSlot.HEAD).filter(s -> has(p, s)).count());
        }
    }

    @Test
    void aSwordInTheOffhandIsSwappedIntoTheMainHandAndTheShieldTakesItsPlace() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = bare(p);
        inv.setItem(9, new ItemStack(Material.IRON_BOOTS));
        inv.setItem(10, new ItemStack(Material.IRON_LEGGINGS));
        inv.setItem(11, new ItemStack(Material.IRON_CHESTPLATE));
        inv.setItem(12, new ItemStack(Material.IRON_HELMET));
        inv.setItem(13, new ItemStack(Material.SHIELD));
        inv.setItemInOffHand(new ItemStack(Material.IRON_SWORD));
        int before = totalItems(p);
        run(squad.get(0), "squad", "start");
        tick(100);
        assertEquals(Material.IRON_SWORD, inv.getItemInMainHand().getType());
        assertEquals(Material.SHIELD, inv.getItemInOffHand().getType());
        assertEquals(before, totalItems(p));
    }

    @Test
    void anOccupiedOffhandIsSwappedAndTheOldItemGoesBackToTheInventory() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        p.getInventory().setItemInOffHand(new ItemStack(Material.TORCH, 8));
        int before = totalItems(p);
        run(squad.get(0), "squad", "start");
        tick(100);
        assertEquals(Material.SHIELD, p.getInventory().getItemInOffHand().getType());
        assertTrue(p.getInventory().contains(Material.TORCH));
        assertEquals(before, totalItems(p));
    }

    // ------------------------------------------------------------------ materials, metadata, inventories

    @Test
    void mixedMaterialsAndEnchantedNamedItemsKeepEverythingFromTheHand() {
        PlayerMock p = server.addPlayer("Solo");
        PlayerInventory inv = bare(p);
        inv.setItem(9, new ItemStack(Material.LEATHER_BOOTS));
        inv.setItem(10, new ItemStack(Material.CHAINMAIL_LEGGINGS));
        inv.setItem(11, new ItemStack(Material.NETHERITE_CHESTPLATE));
        inv.setItem(13, new ItemStack(Material.SHIELD));
        ItemStack helmet = new ItemStack(Material.DIAMOND_HELMET);
        helmet.addUnsafeEnchantment(Enchantment.PROTECTION, 5);
        helmet.editMeta(meta -> meta.displayName(Component.text("Crown")));
        ItemStack original = helmet.clone();
        inv.setItem(0, helmet);

        solo(p);

        assertTrue(original.isSimilar(inv.getHelmet()), "same enchantments and name");
        assertEquals(Material.CHAINMAIL_LEGGINGS, inv.getLeggings().getType());
    }

    @Test
    void aCompletelyFullInventoryStillEquipsAndNothingIsLost() {
        PlayerMock p = server.addPlayer("Solo");
        PlayerInventory inv = bare(p);
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, new ItemStack(Material.DIRT, 64));
        }
        inv.setItem(5, new ItemStack(Material.IRON_BOOTS));
        inv.setItem(6, new ItemStack(Material.IRON_LEGGINGS));
        inv.setItem(7, new ItemStack(Material.IRON_CHESTPLATE));
        inv.setItem(0, new ItemStack(Material.IRON_HELMET));
        inv.setItem(8, new ItemStack(Material.SHIELD));
        int before = totalItems(p);

        solo(p);

        assertEquals(5, countEquipped(p));
        assertEquals(before, totalItems(p));
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
    }

    @Test
    void swappingAWornPieceNeedsRoomAndNeverDropsIt() {
        PlayerMock p = server.addPlayer("Solo");
        PlayerInventory inv = bare(p);
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, new ItemStack(Material.DIRT, 64));
        }
        inv.setItem(5, new ItemStack(Material.IRON_HELMET, 2));
        inv.setHelmet(new ItemStack(Material.LEATHER_HELMET));
        run(p, "settings");
        messages(p);
        p.setOp(true);
        setConfig("equipment.order", List.of("HELMET"));
        int before = totalItems(p);

        run(p, "solo");
        tick(20);

        assertEquals(Material.LEATHER_HELMET, inv.getHelmet().getType(), "no free slot for the old helmet, so nothing moved");
        assertEquals(before, totalItems(p));
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
    }

    // ------------------------------------------------------------------ during and between sequences

    @Test
    void anItemMovedToAnotherSlotMidSequenceIsStillFound() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        run(squad.get(0), "squad", "start");
        tick(3);
        ItemStack helmet = p.getInventory().getItem(12);
        p.getInventory().setItem(12, null);
        p.getInventory().setItem(30, helmet);
        tick(100);
        assertEquals(Material.IRON_HELMET, p.getInventory().getHelmet().getType());
        assertEquals(5, countEquipped(p));
    }

    @Test
    void anItemMovedIntoTheHandMidSequenceIsStillFound() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        run(squad.get(0), "squad", "start");
        tick(3);
        ItemStack helmet = p.getInventory().getItem(12);
        p.getInventory().setItem(12, null);
        p.getInventory().setItem(0, helmet);
        p.getInventory().setItem(14, new ItemStack(Material.IRON_SWORD));
        tick(100);
        assertEquals(Material.IRON_HELMET, p.getInventory().getHelmet().getType());
        assertEquals(Material.IRON_SWORD, p.getInventory().getItemInMainHand().getType());
    }

    @Test
    void equippingAndUnequippingRepeatedlyKeepsEveryItemExactly() {
        List<PlayerMock> squad = formSquad("NULL", 4);
        ItemStack helmet = new ItemStack(Material.IRON_HELMET);
        helmet.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        squad.get(1).getInventory().setItem(12, helmet.clone());
        int before = totalItems(squad.get(1));
        for (int cycle = 0; cycle < 6; cycle++) {
            run(squad.get(0), "squad", "start");
            tick(60);
            assertEquals(5, countEquipped(squad.get(1)), "cycle " + cycle);
            run(squad.get(0), "squad", "unequip");
            tick(60);
            assertEquals(0, countEquipped(squad.get(1)), "cycle " + cycle);
            assertEquals(before, totalItems(squad.get(1)));
        }
        assertTrue(java.util.Arrays.stream(squad.get(1).getInventory().getStorageContents())
                .anyMatch(i -> i != null && i.isSimilar(helmet)), "the enchanted helmet came back unchanged");
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
    }

    @Test
    void changingLoadoutAndSpeedBetweenRunsStillKeepsEveryoneInSync() {
        List<PlayerMock> squad = formSquad("NULL", 6);
        run(squad.get(0), "squad", "speed", "1");
        run(squad.get(0), "squad", "start");
        tick(60);
        run(squad.get(0), "squad", "unequip");
        tick(60);
        run(squad.get(0), "squad", "speed", "6");
        run(squad.get(0), "squad", "loadout", "set", "helmet", "none");
        run(squad.get(0), "squad", "start");
        var ticks = recordFilled(squad, 100);
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.OFF_HAND}) {
            assertEquals(1, squad.stream().map(p -> ticks.get(p.getUniqueId()).get(slot)).distinct().count(), slot.name());
        }
        assertTrue(squad.stream().noneMatch(p -> has(p, EquipmentSlot.HEAD)));
    }

    @Test
    void disconnectingWithAHeldHelmetMidSequenceLeavesAConsistentInventory() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = bare(p);
        fullIronKitExceptHelmet(inv);
        inv.setItem(0, new ItemStack(Material.IRON_HELMET));
        inv.setItem(14, new ItemStack(Material.IRON_SWORD));
        int before = totalItems(p);
        run(squad.get(0), "squad", "start");
        tick(4);
        p.disconnect();
        tick(60);
        assertEquals(before, totalItems(p));
        assertEquals(5, countEquipped(squad.get(2)));
    }

    @Test
    void dyingMidSequenceNeverDuplicatesAHeldItem() {
        PlayerMock p = server.addPlayer("Solo");
        PlayerInventory inv = bare(p);
        fullIronKitExceptHelmet(inv);
        inv.setItem(0, new ItemStack(Material.IRON_HELMET));
        int before = totalItems(p);
        p.setOp(true);
        run(p, "solo");
        tick(3);
        p.setHealth(0);
        tick(30);
        assertTrue(totalItems(p) <= before);
    }

    @Test
    void soloAndSquadStillAgreeOnEverythingForTheSameKit() {
        PlayerMock solo = server.addPlayer("Lone");
        PlayerInventory inv = bare(solo);
        for (int i = 0; i < 4; i++) {
            inv.setItem(9 + i, new ItemStack(new Material[]{Material.IRON_BOOTS, Material.IRON_LEGGINGS,
                    Material.IRON_CHESTPLATE, Material.IRON_HELMET}[i]));
        }
        inv.setItem(13, new ItemStack(Material.SHIELD));
        solo(solo);
        assertEquals(5, countEquipped(solo));

        List<PlayerMock> squad = formSquad("NULL", 3);
        run(squad.get(0), "squad", "start");
        tick(60);
        assertTrue(squad.stream().allMatch(p -> countEquipped(p) == 5));
    }
}
