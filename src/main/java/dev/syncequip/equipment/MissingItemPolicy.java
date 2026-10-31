package dev.syncequip.equipment;

/** What happens when a step's item is unavailable (missing from the inventory or slot occupied). */
public enum MissingItemPolicy {
    /** Refuse to start; if it happens mid-sequence, stop that player's part of the sequence. */
    ABORT,
    /** Skip the unavailable step and continue with the rest. */
    SKIP
}
