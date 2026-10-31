package dev.syncequip.synchronization;

/** Why a participant left a sequence before it finished. */
public enum RemovalReason {
    DISCONNECTED,
    DIED,
    CHANGED_WORLD,
    LEFT_SQUAD,
    ITEM_UNAVAILABLE,
    INVALID_STATE
}
