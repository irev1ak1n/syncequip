package dev.syncequip.equipment;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Each call is one atomic move on the main thread. The same item stack is moved, so nothing is copied or lost. */
public final class EquipmentManager {

    public enum Status {
        EQUIPPED,
        /** The slot already held an item the loadout accepts, so nothing had to move. */
        ALREADY,
        MISSING,
        OCCUPIED,
        INVALID_PLAYER
    }

    public record Result(Status status, ItemStack item) {

        public boolean equipped() {
            return status == Status.EQUIPPED;
        }
    }

    public enum UnequipStatus {
        REMOVED,
        NOTHING,
        NO_ROOM,
        INVALID_PLAYER
    }

    public record UnequipResult(UnequipStatus status, ItemStack item) {

        public boolean removed() {
            return status == UnequipStatus.REMOVED;
        }
    }

    public Result equip(Player player, EquipmentStep step, boolean requireEmptySlot, boolean silent) {
        return equip(player, step, SlotRequirement.ANY, requireEmptySlot, silent, false);
    }

    public Result equip(Player player, EquipmentStep step, SlotRequirement requirement, boolean requireEmptySlot,
                        boolean silent, boolean acceptExisting) {
        if (!InventoryValidator.canEquip(player)) {
            return new Result(Status.INVALID_PLAYER, null);
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack current = InventoryValidator.current(inventory, step);
        boolean occupied = current != null;
        if (occupied && acceptExisting && requirement.matches(step, current.getType())) {
            return new Result(Status.ALREADY, current);
        }
        if (occupied && requireEmptySlot) {
            return new Result(Status.OCCUPIED, null);
        }
        int sourceSlot = InventoryValidator.findSourceSlot(inventory, step, requirement);
        if (sourceSlot < 0) {
            return new Result(Status.MISSING, null);
        }
        ItemStack source = inventory.getItem(sourceSlot);
        if (step.isHand()) {
            return moveHandItem(player, step, sourceSlot, source, current, silent);
        }

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

    /**
     * Main hand and offhand take the whole stack and swap with whatever is there, so no item is lost. The
     * main hand is the slot the player has selected, and the selection never changes.
     */
    private Result moveHandItem(Player player, EquipmentStep step, int sourceSlot, ItemStack source,
                                ItemStack current, boolean silent) {
        PlayerInventory inventory = player.getInventory();
        ItemStack moving = source.clone();
        inventory.setItem(sourceSlot, current == null ? null : current.clone());
        if (step == EquipmentStep.MAIN_HAND) {
            inventory.setItem(inventory.getHeldItemSlot(), moving);
        } else {
            player.getEquipment().setItem(EquipmentSlot.OFF_HAND, moving, silent);
        }
        return new Result(Status.EQUIPPED, moving);
    }

    /**
     * Moves the item in this slot back into the inventory, if the requirement accepts it. The item goes to a free
     * storage slot (or joins a stack), never the held slot and never onto the ground.
     */
    public UnequipResult unequip(Player player, EquipmentStep step, SlotRequirement requirement, boolean silent) {
        if (!InventoryValidator.canEquip(player)) {
            return new UnequipResult(UnequipStatus.INVALID_PLAYER, null);
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack current = InventoryValidator.current(inventory, step);
        if (current == null || !requirement.matches(step, current.getType())) {
            return new UnequipResult(UnequipStatus.NOTHING, null);
        }
        int target = InventoryValidator.findRoomSlot(inventory, current);
        if (target < 0) {
            return new UnequipResult(UnequipStatus.NO_ROOM, null);
        }
        ItemStack moving = current.clone();
        ItemStack there = inventory.getItem(target);
        if (there == null || there.isEmpty()) {
            inventory.setItem(target, moving);
        } else {
            ItemStack merged = there.clone();
            merged.setAmount(there.getAmount() + moving.getAmount());
            inventory.setItem(target, merged);
        }
        if (step == EquipmentStep.MAIN_HAND) {
            inventory.setItem(inventory.getHeldItemSlot(), null);
        } else {
            player.getEquipment().setItem(step.slot(), null, silent);
        }
        return new UnequipResult(UnequipStatus.REMOVED, moving);
    }
}
