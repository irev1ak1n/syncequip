package dev.syncequip.synchronization;

/** Result of executing one step for one participant. */
public enum StepOutcome {
    /** The item was moved into its equipment slot. */
    EQUIPPED,
    /** The step was skipped (item unavailable and policy is SKIP). */
    SKIPPED,
    /** The participant leaves the sequence (item unavailable under ABORT, or player no longer valid). */
    ABORTED
}
