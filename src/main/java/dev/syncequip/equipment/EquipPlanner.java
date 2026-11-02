package dev.syncequip.equipment;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The single place that decides where an item for a slot comes from and what happens to whatever is in the way.
 *
 * <p>The pre-start check and the real move both call {@link #plan} and {@link #apply}. The check runs them on a
 * private copy of the inventory, one step after another, so it sees exactly what the real sequence will see: a
 * helmet held in the hand is gone from the hand by the time the sword step runs, and one item can never be promised
 * to two slots. Slot numbers follow the player inventory: 0 to 35 storage (the selected one is the main hand), 36 to
 * 39 boots, leggings, chestplate and helmet, 40 the offhand.
 */
public final class EquipPlanner {

    static final int STORAGE = 36;
    static final int OFFHAND = 40;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST,
            EquipmentSlot.HEAD};

    private EquipPlanner() {
    }

    /** Read and write access to the 41 slots of one player, real or simulated. */
    public interface Slots {

        int held();

        /** The item in a slot, or {@code null} if it is empty. */
        ItemStack get(int index);

        /** Puts an item in a slot ({@code null} empties it). {@code silent} hides the vanilla equip sound. */
        void set(int index, ItemStack item, boolean silent);

        /** Changes the selected hotbar slot. */
        void select(int hotbarSlot);
    }

    /** The slots of a real player. */
    public static Slots live(Player player) {
        PlayerInventory inventory = player.getInventory();
        return new Slots() {
            @Override
            public int held() {
                return inventory.getHeldItemSlot();
            }

            @Override
            public ItemStack get(int index) {
                ItemStack item = index < STORAGE ? inventory.getItem(index) : inventory.getItem(equipmentSlot(index));
                return item == null || item.isEmpty() ? null : item;
            }

            @Override
            public void set(int index, ItemStack item, boolean silent) {
                if (index < STORAGE) {
                    inventory.setItem(index, item);
                } else {
                    player.getEquipment().setItem(equipmentSlot(index), item, silent);
                }
            }

            @Override
            public void select(int hotbarSlot) {
                inventory.setHeldItemSlot(hotbarSlot);
            }
        };
    }

    /** A private copy that can be changed freely to try out a plan. */
    public static Slots copyOf(Player player) {
        Slots real = live(player);
        ItemStack[] items = new ItemStack[OFFHAND + 1];
        for (int i = 0; i <= OFFHAND; i++) {
            ItemStack item = real.get(i);
            items[i] = item == null ? null : item.clone();
        }
        int[] held = {real.held()};
        return new Slots() {
            @Override
            public int held() {
                return held[0];
            }

            @Override
            public void select(int hotbarSlot) {
                held[0] = hotbarSlot;
            }

            @Override
            public ItemStack get(int index) {
                return items[index];
            }

            @Override
            public void set(int index, ItemStack item, boolean silent) {
                items[index] = item == null || item.isEmpty() ? null : item;
            }
        };
    }

    private static EquipmentSlot equipmentSlot(int index) {
        return index == OFFHAND ? EquipmentSlot.OFF_HAND : ARMOR[index - STORAGE];
    }

    /** Where the item for this step ends up: an armor or offhand slot, or the selected hotbar slot. */
    public static int destination(EquipmentStep step, int held) {
        return switch (step) {
            case BOOTS -> 36;
            case LEGGINGS -> 37;
            case CHESTPLATE -> 38;
            case HELMET -> 39;
            case SHIELD -> OFFHAND;
            case MAIN_HAND -> held;
        };
    }

    public enum Status {
        /** The slot already holds an item the loadout accepts. */
        ALREADY,
        /** The slot holds something else and the settings say not to touch it, or the old item has nowhere to go. */
        OCCUPIED,
        /** No item that fits exists anywhere the player carries items. */
        MISSING,
        /** A fitting item exists, but another slot of the loadout is already using it. */
        CONFLICT,
        MOVE
    }

    /**
     * @param from         slot the item comes from
     * @param destination  slot it goes to
     * @param displacedTo  where the item that was in the way goes, or -1 if the destination was empty. When
     *                     {@code from} equals {@code destination} the item is already on the hotbar and is selected.
     */
    public record Move(int from, int destination, int displacedTo) {
    }

    public record Decision(Status status, Move move) {
    }

    /**
     * Works out what to do for one step.
     *
     * @param loadout        the whole loadout, so items another slot already uses are left alone; may be {@code null}
     * @param acceptExisting whether an item already in place counts (and is therefore kept for its own slot)
     */
    public static Decision plan(Slots slots, Loadout loadout, EquipmentStep step, SlotRequirement requirement,
                                boolean requireEmpty, boolean acceptExisting) {
        int held = slots.held();
        int destination = destination(step, held);
        ItemStack current = slots.get(destination);
        if (current != null && acceptExisting && requirement.matches(step, current.getType())) {
            return new Decision(Status.ALREADY, null);
        }
        if (current != null && requireEmpty) {
            return new Decision(Status.OCCUPIED, null);
        }

        // Quietest sources first: ordinary slots, then the selected one, then the offhand.
        int from = -1;
        boolean reservedHit = false;
        for (int index : searchOrder(held)) {
            if (index == destination) {
                continue;
            }
            ItemStack item = slots.get(index);
            if (item == null || !requirement.matches(step, item.getType())) {
                continue;
            }
            if (reservedByAnotherSlot(slots, loadout, acceptExisting, step, index, item)) {
                reservedHit = true;
                continue;
            }
            from = index;
            break;
        }
        if (from < 0) {
            return new Decision(reservedHit ? Status.CONFLICT : Status.MISSING, null);
        }

        // A weapon already on the hotbar is switched to, so no item has to move at all.
        if (step.isHand() && step == EquipmentStep.MAIN_HAND && from < 9) {
            return new Decision(Status.MOVE, new Move(from, from, -1));
        }

        int displacedTo = -1;
        if (current != null) {
            ItemStack source = slots.get(from);
            // The old item takes the source slot when that slot is emptied, otherwise it needs a free storage slot.
            boolean sourceEmptied = step.isHand() || (source.getAmount() == 1 && from < STORAGE);
            displacedTo = sourceEmptied ? from : firstEmptyStorage(slots);
            if (displacedTo < 0) {
                return new Decision(Status.OCCUPIED, null);
            }
        }
        return new Decision(Status.MOVE, new Move(from, destination, displacedTo));
    }

    private static int[] searchOrder(int held) {
        int[] order = new int[STORAGE + 1];
        int n = 0;
        for (int i = 0; i < STORAGE; i++) {
            if (i != held) {
                order[n++] = i;
            }
        }
        order[n++] = held;
        order[n] = OFFHAND;
        return order;
    }

    /** An item that another enabled slot of the loadout already accepts where it is stays with that slot. */
    private static boolean reservedByAnotherSlot(Slots slots, Loadout loadout, boolean acceptExisting,
                                                 EquipmentStep step, int index, ItemStack item) {
        if (loadout == null || !acceptExisting) {
            return false;
        }
        for (EquipmentStep other : loadout.steps()) {
            if (other != step && destination(other, slots.held()) == index
                    && loadout.requirement(other).matches(other, item.getType())) {
                return true;
            }
        }
        return false;
    }

    private static int firstEmptyStorage(Slots slots) {
        for (int i = 0; i < STORAGE; i++) {
            if (slots.get(i) == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Carries out a planned move. Armor moves one item out of its stack. Hand items move as a whole stack and swap
     * with what was there, so nothing is created, lost or overwritten.
     *
     * @return the item now in the destination slot
     */
    public static ItemStack apply(Slots slots, EquipmentStep step, Move move, boolean silent) {
        ItemStack source = slots.get(move.from());
        if (move.from() == move.destination()) {
            slots.select(move.from());
            return source;
        }
        ItemStack current = slots.get(move.destination());
        ItemStack placed;
        if (step.isHand()) {
            placed = source.clone();
            slots.set(move.from(), current == null ? null : current.clone(), silent);
        } else {
            placed = source.asOne();
            slots.set(move.from(), source.getAmount() > 1 ? source.asQuantity(source.getAmount() - 1) : null, silent);
            if (current != null) {
                slots.set(move.displacedTo(), current.clone(), silent);
            }
        }
        slots.set(move.destination(), placed, silent);
        return placed;
    }
}
