package dev.syncequip.equipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.PluginTestBase;
import dev.syncequip.config.ConfigManager;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class InventoryValidatorTest extends PluginTestBase {

    @Test
    void fullKitIsAllAvailable() {
        PlayerMock player = playerWithKit("Steve");
        InventoryValidator.Report report = InventoryValidator.validate(player, ConfigManager.DEFAULT_ORDER, true);
        assertTrue(report.allAvailable());
        assertEquals(ConfigManager.DEFAULT_ORDER, report.available());
    }

    @Test
    void categorisesMissingAndOccupied() {
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setItem(12, null);
        player.getInventory().setItemInOffHand(new ItemStack(Material.TORCH));

        InventoryValidator.Report report = InventoryValidator.validate(player, ConfigManager.DEFAULT_ORDER, true);

        assertEquals(List.of(EquipmentStep.HELMET), report.missing());
        assertEquals(List.of(EquipmentStep.SHIELD), report.occupied());
        assertFalse(report.acceptableFor(MissingItemPolicy.ABORT));
        assertTrue(report.acceptableFor(MissingItemPolicy.SKIP));
    }

    @Test
    void occupiedIsIgnoredWhenSwappingIsAllowed() {
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        assertTrue(InventoryValidator.validate(player, ConfigManager.DEFAULT_ORDER, false).allAvailable());
    }

    @Test
    void emptyInventoryHasNothingAvailable() {
        PlayerMock player = server.addPlayer();
        InventoryValidator.Report report = InventoryValidator.validate(player, ConfigManager.DEFAULT_ORDER, true);
        assertTrue(report.noneAvailable());
        assertFalse(report.acceptableFor(MissingItemPolicy.SKIP));
    }
}
