package dev.syncequip.equipment;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Moves single items from a player's inventory into an equipment slot.
 *
 * <p>Each call is one self-contained transaction that runs synchronously on the main thread: it
 * re-reads the inventory, re-checks the destination and source, and only then performs the move.
 * The exact {@link ItemStack} (with enchantments, name, damage, trims, banner patterns and any other
 * data) is moved; nothing is recreated. The total number of items the player owns never changes, so
 * an interrupted sequence can never duplicate or lose items, and no inventory snapshot is ever
 * restored.
 */
public final class EquipmentManager {

    public enum Status {
        EQUIPPED,
        MISSING,
        OCCUPIED,
        INVALID_PLAYER
    }

    /**
     * @param status what happened
     * @param item   the item now in the equipment slot when {@code status} is EQUIPPED, else {@code null}
     */
    public record Result(Status status, ItemStack item) {

        public boolean equipped() {
            return status == Status.EQUIPPED;
        }
    }

    /**
     * Equips the item for {@code step}.
     *
     * @param requireEmptySlot if {@code true}, an occupied destination fails with OCCUPIED. If
     *                         {@code false}, the piece currently worn is swapped into the inventory
     *                         slot the new piece came from (never dropped or deleted).
     * @param silent           suppress the vanilla equip sound (used when SyncEquip plays its own)
     */
    public Result equip(Player player, EquipmentStep step, boolean requireEmptySlot, boolean silent) {
        if (!InventoryValidator.canEquip(player)) {
            return new Result(Status.INVALID_PLAYER, null);
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(step.slot());
        boolean occupied = current != null && !current.isEmpty();
        if (occupied && requireEmptySlot) {
            return new Result(Status.OCCUPIED, null);
        }
        int sourceSlot = InventoryValidator.findSourceSlot(inventory, step);
        if (sourceSlot < 0) {
            return new Result(Status.MISSING, null);
        }

        ItemStack source = inventory.getItem(sourceSlot);
        ItemStack toEquip = source.asOne();
        ItemStack sourceRemainder = source.getAmount() > 1 ? source.asQuantity(source.getAmount() - 1) : null;

        ItemStack displaced = occupied ? current : null;
        int displacedSlot = -1;
        if (displaced != null) {
            if (sourceRemainder == null) {
                displacedSlot = sourceSlot;
            } else {
                // The source slot stays in use (stacked armor is unusual but possible), so the worn
                // piece needs a free slot of its own; refuse rather than drop it.
                displacedSlot = inventory.firstEmpty();
                if (displacedSlot < 0) {
                    return new Result(Status.OCCUPIED, null);
                }
            }
        }

        inventory.setItem(sourceSlot, sourceRemainder);
        if (displaced != null && displacedSlot != sourceSlot) {
            inventory.setItem(displacedSlot, displaced);
        } else if (displaced != null) {
            inventory.setItem(sourceSlot, displaced);
        }
        player.getEquipment().setItem(step.slot(), toEquip, silent);
        return new Result(Status.EQUIPPED, toEquip);
    }
}
