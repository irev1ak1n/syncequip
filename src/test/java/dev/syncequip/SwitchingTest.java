package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Players switch to what the loadout needs instead of being refused because something else is in the way. */
class SwitchingTest extends V11TestBase {
    private PlayerInventory cleared(PlayerMock p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setHeldItemSlot(0);
        inv.setItem(9, new ItemStack(Material.IRON_BOOTS));
        inv.setItem(10, new ItemStack(Material.IRON_LEGGINGS));
        inv.setItem(11, new ItemStack(Material.IRON_CHESTPLATE));
        inv.setItem(12, new ItemStack(Material.IRON_HELMET));
        inv.setItem(13, new ItemStack(Material.SHIELD));
        return inv;
    }

    private void startSquad(List<PlayerMock> squad) {
        run(squad.get(0), "squad", "start");
        tick(100);
    }

    @Test
    void anAxeInHandWithTheSwordElsewhereOnTheHotbarSwitchesTheSelectedSlot() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = cleared(p);
        inv.setItem(0, new ItemStack(Material.IRON_AXE));
        inv.setItem(5, new ItemStack(Material.IRON_SWORD));
        int before = totalItems(p);

        startSquad(squad);

        assertFalse(received(squad.get(0), "Cannot start"));
        assertEquals(5, inv.getHeldItemSlot());
        assertEquals(Material.IRON_SWORD, inv.getItemInMainHand().getType());
        assertEquals(Material.IRON_AXE, inv.getItem(0).getType(), "the axe stays where it was");
        assertEquals(before, totalItems(p));
    }

    @Test
    void theSwordAlreadySelectedIsLeftAlone() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = cleared(p);
        inv.setItem(0, new ItemStack(Material.IRON_SWORD));
        inv.setHeldItemSlot(0);

        startSquad(squad);

        assertEquals(0, inv.getHeldItemSlot());
        assertEquals(Material.IRON_SWORD, inv.getItem(0).getType());
    }

    @Test
    void aSwordInTheMainInventoryIsSwappedIntoTheSelectedSlot() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = cleared(p);
        inv.setItem(0, new ItemStack(Material.DIAMOND_PICKAXE));
        inv.setItem(20, new ItemStack(Material.IRON_SWORD));
        int before = totalItems(p);

        startSquad(squad);

        assertEquals(0, inv.getHeldItemSlot());
        assertEquals(Material.IRON_SWORD, inv.getItem(0).getType());
        assertEquals(Material.DIAMOND_PICKAXE, inv.getItem(20).getType());
        assertEquals(before, totalItems(p));
    }

    @Test
    void anEmptyHandTakesTheSwordWithoutMovingAnythingElse() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = cleared(p);
        inv.setItem(30, new ItemStack(Material.IRON_SWORD));

        startSquad(squad);

        assertEquals(Material.IRON_SWORD, inv.getItemInMainHand().getType());
        assertTrue(inv.getItem(30) == null || inv.getItem(30).isEmpty());
    }

    @Test
    void aTotemInTheOffhandIsReplacedByTheShieldAndComesBack() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = cleared(p);
        inv.setItem(0, new ItemStack(Material.IRON_SWORD));
        inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        int before = totalItems(p);

        startSquad(squad);

        assertEquals(Material.SHIELD, inv.getItemInOffHand().getType());
        assertTrue(inv.contains(Material.TOTEM_OF_UNDYING));
        assertEquals(before, totalItems(p));
    }

    @Test
    void anyConfiguredOffhandItemIsSupported() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(0), "squad", "loadout", "set", "offhand", "totem_of_undying");
        PlayerMock p = squad.get(1);
        PlayerInventory inv = cleared(p);
        inv.setItem(0, new ItemStack(Material.IRON_SWORD));
        inv.setItem(14, new ItemStack(Material.TOTEM_OF_UNDYING));
        inv.setItemInOffHand(new ItemStack(Material.SHIELD));

        startSquad(squad);

        assertEquals(Material.TOTEM_OF_UNDYING, inv.getItemInOffHand().getType());
        assertTrue(inv.contains(Material.SHIELD));
    }

    @Test
    void wornArmorIsReplacedAndKeepsItsEnchantmentsAndName() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        run(squad.get(0), "squad", "loadout", "set", "helmet", "iron_helmet");
        run(squad.get(0), "squad", "loadout", "set", "boots", "iron_boots");
        PlayerMock p = squad.get(1);
        PlayerInventory inv = cleared(p);
        inv.setItem(0, new ItemStack(Material.IRON_SWORD));
        ItemStack old = new ItemStack(Material.DIAMOND_HELMET);
        old.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        old.editMeta(meta -> meta.displayName(Component.text("Old crown")));
        ItemStack copy = old.clone();
        inv.setHelmet(old);
        inv.setBoots(new ItemStack(Material.LEATHER_BOOTS));
        int before = totalItems(p);

        startSquad(squad);

        assertEquals(Material.IRON_HELMET, inv.getHelmet().getType());
        assertEquals(Material.IRON_BOOTS, inv.getBoots().getType());
        assertTrue(java.util.Arrays.stream(inv.getStorageContents()).anyMatch(copy::isSimilar), "metadata kept");
        assertEquals(before, totalItems(p));
    }

    @Test
    void alreadyCorrectEquipmentCausesNoError() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = cleared(p);
        inv.clear();
        inv.setItem(0, new ItemStack(Material.IRON_SWORD));
        inv.setHelmet(new ItemStack(Material.IRON_HELMET));
        inv.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        inv.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        inv.setBoots(new ItemStack(Material.IRON_BOOTS));
        inv.setItemInOffHand(new ItemStack(Material.SHIELD));

        startSquad(squad);

        assertFalse(received(squad.get(0), "Cannot start"));
        assertEquals(5, countEquipped(p));
    }

    @Test
    void aFullInventoryStillSwapsAndNothingIsDropped() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setHeldItemSlot(0);
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, new ItemStack(Material.DIRT, 64));
        }
        inv.setItem(0, new ItemStack(Material.IRON_AXE));
        inv.setItem(9, new ItemStack(Material.IRON_BOOTS));
        inv.setItem(10, new ItemStack(Material.IRON_LEGGINGS));
        inv.setItem(11, new ItemStack(Material.IRON_CHESTPLATE));
        inv.setItem(12, new ItemStack(Material.IRON_HELMET));
        inv.setItem(13, new ItemStack(Material.SHIELD));
        inv.setItem(14, new ItemStack(Material.IRON_SWORD));
        inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        int before = totalItems(p);

        startSquad(squad);

        assertEquals(Material.IRON_SWORD, inv.getItemInMainHand().getType());
        assertEquals(Material.SHIELD, inv.getItemInOffHand().getType());
        assertEquals(5, countEquipped(p));
        assertEquals(before, totalItems(p));
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
    }

    @Test
    void aMemberWhoTrulyHasNoSwordIsLeftOutAndTheRestStillEquip() {
        List<PlayerMock> squad = formSquad("NULL", 3);
        PlayerMock noSword = squad.get(2);
        cleared(noSword).setItem(0, new ItemStack(Material.IRON_AXE));

        startSquad(squad);

        assertTrue(received(squad.get(0), "without 1 member"));
        assertEquals(5, countEquipped(squad.get(1)));
        assertEquals(Material.IRON_AXE, noSword.getInventory().getItem(0).getType(), "untouched");
    }

    @Test
    void repeatedEquipAndUnequipRoundsKeepEveryItem() {
        List<PlayerMock> squad = formSquad("NULL", 2);
        PlayerMock p = squad.get(1);
        PlayerInventory inv = cleared(p);
        inv.setItem(0, new ItemStack(Material.IRON_AXE));
        inv.setItem(6, new ItemStack(Material.IRON_SWORD));
        inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        int before = totalItems(p);

        for (int round = 0; round < 3; round++) {
            startSquad(squad);
            assertEquals(5, countEquipped(p), "round " + round);
            assertEquals(Material.IRON_SWORD, inv.getItemInMainHand().getType());
            run(squad.get(0), "squad", "unequip");
            tick(100);
            assertEquals(before, totalItems(p), "round " + round);
        }
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
    }

    @Test
    void everyPlayerSwitchesWeaponOnTheSameTickWhateverTheyHold() {
        List<PlayerMock> squad = formSquad("NULL", 4);
        for (int i = 0; i < squad.size(); i++) {
            PlayerInventory inv = cleared(squad.get(i));
            inv.setItem(0, new ItemStack(i % 2 == 0 ? Material.IRON_AXE : Material.DIAMOND_PICKAXE));
            if (i < 2) {
                inv.setItem(3 + i, new ItemStack(Material.IRON_SWORD));
            } else {
                inv.setItem(20 + i, new ItemStack(Material.IRON_SWORD));
            }
        }

        run(squad.get(0), "squad", "start");
        List<Long> switchedAt = new ArrayList<>();
        PlayerMock[] players = squad.toArray(new PlayerMock[0]);
        Long[] seen = new Long[players.length];
        for (int t = 1; t <= 100; t++) {
            server.getScheduler().performOneTick();
            for (int i = 0; i < players.length; i++) {
                if (seen[i] == null && players[i].getInventory().getItemInMainHand().getType() == Material.IRON_SWORD) {
                    seen[i] = (long) t;
                }
            }
        }
        for (Long tick : seen) {
            assertNotNull(tick, "everyone ended up holding the sword");
            switchedAt.add(tick);
        }
        assertEquals(1, switchedAt.stream().distinct().count(), "same tick: " + switchedAt);
    }
}
