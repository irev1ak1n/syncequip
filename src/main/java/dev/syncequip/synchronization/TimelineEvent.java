package dev.syncequip.synchronization;

import dev.syncequip.equipment.EquipmentStep;

/**
 * A single event on a sequence timeline, positioned at a tick offset from the sequence start.
 *
 * @param type   what kind of event this is
 * @param index  seconds remaining (for {@link Type#COUNTDOWN}) or step index (for {@link Type#STEP})
 * @param step   the equipment step, only for {@link Type#STEP}
 * @param offset ticks after the sequence's start tick
 */
public record TimelineEvent(Type type, int index, EquipmentStep step, long offset) {

    public enum Type {
        COUNTDOWN,
        STEP,
        FINISH
    }
}
