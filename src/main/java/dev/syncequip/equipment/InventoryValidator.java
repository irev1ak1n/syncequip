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

    public static ItemStack current(PlayerInventory inventory, EquipmentStep step) {
        ItemStack item = step == EquipmentStep.MAIN_HAND
                ? inventory.getItem(inventory.getHeldItemSlot())
                : inventory.getItem(step.slot());
        return item == null || item.isEmpty() ? null : item;
    }

    public static boolean isDestinationOccupied(PlayerInventory inventory, EquipmentStep step) {
        return current(inventory, step) != null;
    }

    public static boolean canEquip(Player player) {
        return player.isOnline() && player.isValid() && !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    public static Report validate(Player player, List<EquipmentStep> order, boolean requireEmptySlots) {
        return validate(player, Loadout.legacy(order), requireEmptySlots, false);
    }

    public static Report validate(Player player, Loadout loadout, boolean requireEmptySlots, boolean acceptExisting) {
        EquipPlanner.Slots model = EquipPlanner.copyOf(player);
        List<EquipmentStep> available = new ArrayList<>();
        List<EquipmentStep> missing = new ArrayList<>();
        List<EquipmentStep> occupied = new ArrayList<>();
        List<EquipmentStep> satisfied = new ArrayList<>();
        List<EquipmentStep> skippable = new ArrayList<>();
        List<EquipmentStep> conflicts = new ArrayList<>();
        for (EquipmentStep step : loadout.steps()) {
            SlotRequirement req = loadout.requirement(step);
            EquipPlanner.Decision decision = EquipPlanner.plan(model, loadout, step, req, requireEmptySlots,
                    acceptExisting);
            switch (decision.status()) {
                case ALREADY -> satisfied.add(step);
                case MOVE -> {
                    available.add(step);
                    EquipPlanner.apply(model, step, decision.move(), true);
                }
                case MISSING -> (req.optional() ? skippable : missing).add(step);
                case OCCUPIED -> (req.optional() ? skippable : occupied).add(step);
                case CONFLICT -> (req.optional() ? skippable : conflicts).add(step);
            }
        }
        return new Report(available, missing, occupied, satisfied, skippable, conflicts);
    }

    public record Report(List<EquipmentStep> available, List<EquipmentStep> missing, List<EquipmentStep> occupied,
                         List<EquipmentStep> satisfied, List<EquipmentStep> skippable, List<EquipmentStep> conflicts) {
        public Report {
            available = List.copyOf(available);
            missing = List.copyOf(missing);
            occupied = List.copyOf(occupied);
            satisfied = List.copyOf(satisfied);
            skippable = List.copyOf(skippable);
            conflicts = List.copyOf(conflicts);
        }

        public Report(List<EquipmentStep> available, List<EquipmentStep> missing, List<EquipmentStep> occupied) {
            this(available, missing, occupied, List.of(), List.of(), List.of());
        }

        public boolean allAvailable() {
            return missing.isEmpty() && occupied.isEmpty() && conflicts.isEmpty();
        }

        public boolean noneAvailable() {
            return available.isEmpty();
        }

        public boolean acceptableFor(MissingItemPolicy policy) {
            return policy == MissingItemPolicy.ABORT ? allAvailable() : !(available.isEmpty() && satisfied.isEmpty());
        }
    }

    public static int findRoomSlot(PlayerInventory inventory, ItemStack item) {
        ItemStack[] storage = new ItemStack[STORAGE_SLOTS];
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            storage[slot] = inventory.getItem(slot);
        }
        return findRoomSlot(storage, inventory.getHeldItemSlot(), item);
    }

    private static int findRoomSlot(ItemStack[] storage, int held, ItemStack item) {
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            if (slot != held && (storage[slot] == null || storage[slot].isEmpty())) {
                return slot;
            }
        }
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            ItemStack existing = storage[slot];
            if (slot != held && existing != null && !existing.isEmpty() && existing.isSimilar(item)
                    && existing.getAmount() + item.getAmount() <= existing.getMaxStackSize()) {
                return slot;
            }
        }
        return -1;
    }

    public record UnequipReport(List<EquipmentStep> removable, boolean enoughRoom) {
    }

    public static UnequipReport unequipReport(Player player, Loadout loadout) {
        PlayerInventory inventory = player.getInventory();
        int held = inventory.getHeldItemSlot();
        ItemStack[] storage = new ItemStack[STORAGE_SLOTS];
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            storage[slot] = item == null ? null : item.clone();
        }
        List<EquipmentStep> removable = new ArrayList<>();
        boolean room = true;
        for (EquipmentStep step : loadout.unequipSteps()) {
            ItemStack current = current(inventory, step);
            if (current == null || !loadout.requirement(step).matches(step, current.getType())) {
                continue;
            }
            removable.add(step);
            int target = findRoomSlot(storage, held, current);
            if (target < 0) {
                room = false;
                continue;
            }
            ItemStack there = storage[target];
            if (there == null || there.isEmpty()) {
                storage[target] = current.clone();
            } else {
                there.setAmount(there.getAmount() + current.getAmount());
            }
        }
        return new UnequipReport(removable, room);
    }
}
