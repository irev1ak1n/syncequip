package dev.syncequip.synchronization;

import dev.syncequip.equipment.EquipmentStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/** Test double that records every callback the scheduler makes. */
final class RecordingCallbacks implements SequenceCallbacks {

    record Execution(long sequenceId, UUID participant, EquipmentStep step, int index, long scheduled, long actual) {
    }

    record Countdown(long sequenceId, int seconds, long tick) {
    }

    record Removal(UUID participant, RemovalReason reason, int equipped) {
    }

    final List<Execution> executions = new ArrayList<>();
    final List<Countdown> countdowns = new ArrayList<>();
    final List<Removal> removals = new ArrayList<>();
    final List<FinishReason> finishes = new ArrayList<>();
    final List<Long> beforeFirstStep = new ArrayList<>();

    /** Decides the outcome of each step; defaults to always equipping. */
    BiFunction<UUID, EquipmentStep, StepOutcome> outcome = (id, step) -> StepOutcome.EQUIPPED;

    @Override
    public void onCountdown(SyncSequence sequence, int secondsRemaining, long tick) {
        countdowns.add(new Countdown(sequence.id(), secondsRemaining, tick));
    }

    @Override
    public void onBeforeFirstStep(SyncSequence sequence, long tick) {
        beforeFirstStep.add(tick);
    }

    @Override
    public StepOutcome executeStep(SyncSequence sequence, UUID participant, EquipmentStep step, int stepIndex,
                                   long scheduledTick, long actualTick) {
        executions.add(new Execution(sequence.id(), participant, step, stepIndex, scheduledTick, actualTick));
        return outcome.apply(participant, step);
    }

    @Override
    public void onParticipantRemoved(SyncSequence sequence, UUID participant, RemovalReason reason,
                                     int equippedCount) {
        removals.add(new Removal(participant, reason, equippedCount));
    }

    @Override
    public void onFinished(SyncSequence sequence, FinishReason reason) {
        finishes.add(reason);
    }

    List<Execution> forParticipant(UUID participant) {
        return executions.stream().filter(e -> e.participant().equals(participant)).toList();
    }

    List<Execution> forStep(EquipmentStep step) {
        return executions.stream().filter(e -> e.step() == step).toList();
    }
}
