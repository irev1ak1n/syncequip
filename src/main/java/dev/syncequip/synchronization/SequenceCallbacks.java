package dev.syncequip.synchronization;

import dev.syncequip.equipment.EquipmentStep;
import java.util.UUID;

public interface SequenceCallbacks {

    default void onCountdown(SyncSequence sequence, int secondsRemaining, long tick) {
    }

    default void onBeforeFirstStep(SyncSequence sequence, long tick) {
    }

    StepOutcome executeStep(SyncSequence sequence, UUID participant, EquipmentStep step, int stepIndex,
                            long scheduledTick, long actualTick);

    default void afterStep(SyncSequence sequence, EquipmentStep step, int stepIndex, long tick) {
    }

    default void onParticipantRemoved(SyncSequence sequence, UUID participant, RemovalReason reason,
                                      int equippedCount) {
    }

    default void onFinished(SyncSequence sequence, FinishReason reason) {
    }
}
