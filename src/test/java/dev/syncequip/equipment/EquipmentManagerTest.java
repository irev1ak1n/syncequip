package dev.syncequip.equipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.PluginTestBase;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class EquipmentManagerTest extends PluginTestBase {

    private final EquipmentManager manager = new EquipmentManager();

    @Test
    void movesTheItemFromInventoryIntoTheArmorSlot() {
        PlayerMock player = playerWithKit("Steve");
        int before = totalItems(player);

        EquipmentManager.Result result = manager.equip(player, EquipmentStep.BOOTS, true, true);

        assertEquals(EquipmentManager.Status.EQUIPPED, result.status());
        assertEquals(Material.IRON_BOOTS, player.getInventory().getBoots().getType());
        assertNull(player.getInventory().getItem(9), "source slot emptied");
        assertEquals(before, totalItems(player), "no duplication, no loss");
    }

    @Test
    void preservesEnchantmentsNameAndDurability() {
        PlayerMock player = server.addPlayer();
        ItemStack chestplate = new ItemStack(Material.DIAMOND_CHESTPLATE);
        chestplate.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        chestplate.editMeta(meta -> {
            meta.displayName(Component.text("Aegis of the Formation"));
            ((Damageable) meta).setDamage(123);
        });
        ItemStack original = chestplate.clone();
        player.getInventory().setItem(20, chestplate);

        manager.equip(player, EquipmentStep.CHESTPLATE, true, true);

        ItemStack worn = player.getInventory().getChestplate();
        assertTrue(original.isSimilar(worn), "metadata unchanged");
        assertEquals(4, worn.getEnchantmentLevel(Enchantment.PROTECTION));
        assertEquals(123, ((Damageable) worn.getItemMeta()).getDamage());
        assertEquals(Component.text("Aegis of the Formation"), worn.getItemMeta().displayName());
    }

    @Test
    void shieldGoesToOffhandWithItsMetadata() {
        PlayerMock player = server.addPlayer();
        ItemStack shield = new ItemStack(Material.SHIELD);
        shield.editMeta(meta -> meta.displayName(Component.text("Bulwark")));
        player.getInventory().setItem(30, shield.clone());

        assertTrue(manager.equip(player, EquipmentStep.SHIELD, true, true).equipped());

        assertTrue(shield.isSimilar(player.getInventory().getItemInOffHand()));
        assertNull(player.getInventory().getItem(30));
    }

    @Test
    void occupiedSlotIsRefusedAndNothingMoves() {
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setHelmet(new ItemStack(Material.GOLDEN_HELMET));
        int before = totalItems(player);

        EquipmentManager.Result result = manager.equip(player, EquipmentStep.HELMET, true, true);

        assertEquals(EquipmentManager.Status.OCCUPIED, result.status());
        assertEquals(Material.GOLDEN_HELMET, player.getInventory().getHelmet().getType());
        assertEquals(Material.IRON_HELMET, player.getInventory().getItem(12).getType());
        assertEquals(before, totalItems(player));
    }

    @Test
    void occupiedOffhandIsRefused() {
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setItemInOffHand(new ItemStack(Material.TORCH, 16));

        assertEquals(EquipmentManager.Status.OCCUPIED, manager.equip(player, EquipmentStep.SHIELD, true, true).status());
        assertEquals(16, player.getInventory().getItemInOffHand().getAmount());
    }

    @Test
    void swapModeReturnsWornPieceToTheSourceSlot() {
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setHelmet(new ItemStack(Material.GOLDEN_HELMET));
        int before = totalItems(player);

        assertTrue(manager.equip(player, EquipmentStep.HELMET, false, true).equipped());

        assertEquals(Material.IRON_HELMET, player.getInventory().getHelmet().getType());
        assertEquals(Material.GOLDEN_HELMET, player.getInventory().getItem(12).getType());
        assertEquals(before, totalItems(player));
    }

    @Test
    void missingItemIsReported() {
        PlayerMock player = server.addPlayer();
        assertEquals(EquipmentManager.Status.MISSING, manager.equip(player, EquipmentStep.LEGGINGS, true, true).status());
    }

    @Test
    void anItemHeldInTheHandIsAValidSourceAndAWeaponIsNeverTouched() {
        PlayerMock player = server.addPlayer();
        PlayerInventory inventory = player.getInventory();
        inventory.setHeldItemSlot(3);
        inventory.setItem(3, new ItemStack(Material.SHIELD));
        inventory.setItem(4, new ItemStack(Material.IRON_SWORD));

        assertEquals(EquipmentManager.Status.EQUIPPED, manager.equip(player, EquipmentStep.SHIELD, true, true).status());
        assertEquals(Material.SHIELD, inventory.getItemInOffHand().getType());
        assertTrue(inventory.getItemInMainHand().isEmpty(), "the shield left the hand");
        assertEquals(Material.IRON_SWORD, inventory.getItem(4).getType(), "other items are untouched");
        assertEquals(3, inventory.getHeldItemSlot());
    }

    @Test
    void mainHandWeaponIsPreservedThroughAFullKit() {
        PlayerMock player = playerWithKit("Steve");
        for (EquipmentStep step : java.util.List.of(EquipmentStep.BOOTS, EquipmentStep.LEGGINGS, EquipmentStep.CHESTPLATE, EquipmentStep.HELMET, EquipmentStep.SHIELD)) {
            assertTrue(manager.equip(player, step, true, true).equipped(), step.name());
        }
        assertEquals(Material.IRON_SWORD, player.getInventory().getItemInMainHand().getType());
        assertEquals(5, countEquipped(player));
    }

    @Test
    void mixedMaterialsAreSupported() {
        PlayerMock player = server.addPlayer();
        List<Material> kit = List.of(Material.LEATHER_BOOTS, Material.CHAINMAIL_LEGGINGS,
                Material.NETHERITE_CHESTPLATE, Material.TURTLE_HELMET);
        for (int i = 0; i < kit.size(); i++) {
            player.getInventory().setItem(9 + i, new ItemStack(kit.get(i)));
        }
        for (EquipmentStep step : List.of(EquipmentStep.BOOTS, EquipmentStep.LEGGINGS, EquipmentStep.CHESTPLATE,
                EquipmentStep.HELMET)) {
            assertTrue(manager.equip(player, step, true, true).equipped(), step.name());
        }
        assertEquals(Material.LEATHER_BOOTS, player.getInventory().getBoots().getType());
        assertEquals(Material.TURTLE_HELMET, player.getInventory().getHelmet().getType());
    }

    @Test
    void onlyOneOfASecondSetIsTaken() {
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setItem(20, new ItemStack(Material.DIAMOND_BOOTS));

        manager.equip(player, EquipmentStep.BOOTS, true, true);

        assertEquals(Material.IRON_BOOTS, player.getInventory().getBoots().getType(), "first matching slot wins");
        assertEquals(Material.DIAMOND_BOOTS, player.getInventory().getItem(20).getType());
    }

    @Test
    void stackedArmorOnlyMovesOne() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(9, new ItemStack(Material.IRON_BOOTS, 3));

        manager.equip(player, EquipmentStep.BOOTS, true, true);

        assertEquals(1, player.getInventory().getBoots().getAmount());
        assertEquals(2, player.getInventory().getItem(9).getAmount());
    }

    @Test
    void deadPlayersCannotEquip() {
        PlayerMock player = playerWithKit("Steve");
        player.setHealth(0);
        assertEquals(EquipmentManager.Status.INVALID_PLAYER,
                manager.equip(player, EquipmentStep.BOOTS, true, true).status());
    }

    @Test
    void headsPumpkinsAndElytraAreNotArmorSteps() {
        assertFalse(EquipmentStep.HELMET.matches(Material.CARVED_PUMPKIN));
        assertFalse(EquipmentStep.HELMET.matches(Material.PLAYER_HEAD));
        assertFalse(EquipmentStep.CHESTPLATE.matches(Material.ELYTRA));
        assertTrue(EquipmentStep.HELMET.matches(Material.TURTLE_HELMET));
        assertTrue(EquipmentStep.SHIELD.matches(Material.SHIELD));
        assertEquals(EquipmentSlot.OFF_HAND, EquipmentStep.SHIELD.slot());
    }
}
