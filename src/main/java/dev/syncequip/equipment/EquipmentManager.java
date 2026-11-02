package dev.syncequip.equipment;

import org.bukkit.entity.Player;
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
        /** A fitting item exists but another slot of the loadout is using it. */
        CONFLICT,
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
        return equip(player, step, requirement, null, requireEmptySlot, silent, acceptExisting);
    }

    /** @param loadout the whole loadout, so an item another slot is using is not taken; may be {@code null} */
    public Result equip(Player player, EquipmentStep step, SlotRequirement requirement, Loadout loadout,
                        boolean requireEmptySlot, boolean silent, boolean acceptExisting) {
        if (!InventoryValidator.canEquip(player)) {
            return new Result(Status.INVALID_PLAYER, null);
        }
        EquipPlanner.Slots slots = EquipPlanner.live(player);
        EquipPlanner.Decision decision = EquipPlanner.plan(slots, loadout, step, requirement, requireEmptySlot,
                acceptExisting);
        return switch (decision.status()) {
            case ALREADY -> new Result(Status.ALREADY, slots.get(EquipPlanner.destination(step, slots.held())));
            case OCCUPIED -> new Result(Status.OCCUPIED, null);
            case MISSING -> new Result(Status.MISSING, null);
            case CONFLICT -> new Result(Status.CONFLICT, null);
            case MOVE -> new Result(Status.EQUIPPED, EquipPlanner.apply(slots, step, decision.move(), silent));
        };
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
