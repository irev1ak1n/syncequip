package dev.syncequip.equipment;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Each call is one atomic move on the main thread. The same item stack is moved, so nothing is copied or lost. */
public final class EquipmentManager {

    public enum Status {
        EQUIPPED,
        MISSING,
        OCCUPIED,
        INVALID_PLAYER
    }

    public record Result(Status status, ItemStack item) {

        public boolean equipped() {
            return status == Status.EQUIPPED;
        }
    }

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

        // A worn piece is never dropped. If it has no safe slot to go back to, the move is refused instead.
        ItemStack displaced = occupied ? current : null;
        int displacedSlot = -1;
        if (displaced != null) {
            if (sourceRemainder == null) {
                displacedSlot = sourceSlot;
            } else {
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
