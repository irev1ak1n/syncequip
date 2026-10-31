package dev.syncequip.synchronization;

import dev.syncequip.equipment.EquipmentStep;
import java.util.UUID;

/**
 * Hooks invoked by a {@link SyncSequence} as its timeline advances. All calls happen on the thread
 * driving {@link TickSequenceScheduler#tick(long)}, which is the server main thread in production.
 */
public interface SequenceCallbacks {

    /** A countdown beat, called once per beat. */
    default void onCountdown(SyncSequence sequence, int secondsRemaining, long tick) {
    }

    /**
     * Called once on the tick of the first step, before it runs, so participants can be
     * re-validated. Implementations may call {@link SyncSequence#removeParticipant}.
     */
    default void onBeforeFirstStep(SyncSequence sequence, long tick) {
    }

    /** Executes one step for one participant. */
    StepOutcome executeStep(SyncSequence sequence, UUID participant, EquipmentStep step, int stepIndex,
                            long scheduledTick, long actualTick);

    /** Called after a step ran for every participant on this tick. */
    default void afterStep(SyncSequence sequence, EquipmentStep step, int stepIndex, long tick) {
    }

    /** A participant left before the end; {@code equippedCount} says how many pieces they had put on. */
    default void onParticipantRemoved(SyncSequence sequence, UUID participant, RemovalReason reason,
                                      int equippedCount) {
    }

    /** The sequence ended. Called exactly once per sequence. */
    default void onFinished(SyncSequence sequence, FinishReason reason) {
    }
}
