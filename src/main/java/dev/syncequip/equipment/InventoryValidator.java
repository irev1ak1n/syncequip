package dev.syncequip.equipment;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Read-only checks on a player's inventory. Nothing here mutates items; {@link EquipmentManager}
 * performs the actual moves and re-runs these checks inside each transaction.
 */
public final class InventoryValidator {

    /** Number of main storage slots (hotbar 0-8 plus the 27 inventory slots). */
    private static final int STORAGE_SLOTS = 36;

    private InventoryValidator() {
    }

    /**
     * Finds the inventory slot holding the item for {@code step}. The hotbar is scanned first, then
     * the main inventory. The currently held main-hand slot is never used, so the player's weapon
     * (or a shield they are deliberately holding) is left alone.
     *
     * @return the slot index, or {@code -1} if no suitable item exists
     */
    public static int findSourceSlot(PlayerInventory inventory, EquipmentStep step) {
        int held = inventory.getHeldItemSlot();
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
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

    /** Whether the player is in a state where equipment may be changed at all. */
    public static boolean canEquip(Player player) {
        return player.isOnline() && player.isValid() && !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    /** Checks every step of {@code order} without changing anything. */
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

    /** Result of {@link #validate}. Each step of the order appears in exactly one list. */
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

        /** Whether this report allows a sequence to start under {@code policy}. */
        public boolean acceptableFor(MissingItemPolicy policy) {
            return policy == MissingItemPolicy.ABORT ? allAvailable() : !noneAvailable();
        }
    }
}
