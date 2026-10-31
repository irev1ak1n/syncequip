package dev.syncequip.synchronization;

import dev.syncequip.equipment.EquipmentStep;

public record TimelineEvent(Type type, int index, EquipmentStep step, long offset) {

    public enum Type {
        COUNTDOWN,
        STEP,
        FINISH
    }
}
