package dev.syncequip.equipment;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class InventoryValidator {

    private static final int STORAGE_SLOTS = 36;

    private InventoryValidator() {
    }

    public static int findSourceSlot(PlayerInventory inventory, EquipmentStep step) {
        int held = inventory.getHeldItemSlot();
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            // The held slot is skipped so the weapon in the player's main hand always stays where it is.
            if (slot == held) {
                continue;
            }
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty() && step.matches(item.getType())) {
                return slot;
            }
        }
        return -1;
    }

    public static boolean isDestinationOccupied(PlayerInventory inventory, EquipmentStep step) {
        ItemStack current = inventory.getItem(step.slot());
        return current != null && !current.isEmpty();
    }

    public static boolean canEquip(Player player) {
        return player.isOnline() && player.isValid() && !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    public static Report validate(Player player, List<EquipmentStep> order, boolean requireEmptySlots) {
        PlayerInventory inventory = player.getInventory();
        List<EquipmentStep> available = new ArrayList<>();
        List<EquipmentStep> missing = new ArrayList<>();
        List<EquipmentStep> occupied = new ArrayList<>();
        for (EquipmentStep step : order) {
            if (requireEmptySlots && isDestinationOccupied(inventory, step)) {
                occupied.add(step);
            } else if (findSourceSlot(inventory, step) < 0) {
                missing.add(step);
            } else {
                available.add(step);
            }
        }
        return new Report(available, missing, occupied);
    }

    public record Report(List<EquipmentStep> available, List<EquipmentStep> missing, List<EquipmentStep> occupied) {

        public Report {
            available = List.copyOf(available);
            missing = List.copyOf(missing);
            occupied = List.copyOf(occupied);
        }

        public boolean allAvailable() {
            return missing.isEmpty() && occupied.isEmpty();
        }

        public boolean noneAvailable() {
            return available.isEmpty();
        }

        public boolean acceptableFor(MissingItemPolicy policy) {
            return policy == MissingItemPolicy.ABORT ? allAvailable() : !noneAvailable();
        }
    }
}
