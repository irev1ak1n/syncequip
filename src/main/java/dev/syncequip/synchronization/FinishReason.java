package dev.syncequip.synchronization;

/** How a sequence ended. */
public enum FinishReason {
    /** Every step ran. */
    COMPLETED,
    /** Cancelled by a command. */
    CANCELLED,
    /** All participants left. */
    NO_PARTICIPANTS,
    /** The plugin is being disabled. */
    SHUTDOWN
}
