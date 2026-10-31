package dev.syncequip.synchronization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.config.ConfigManager;
import dev.syncequip.equipment.EquipmentStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TickSequenceSchedulerTest {

    private TickSequenceScheduler scheduler;
    private RecordingCallbacks callbacks;

    @BeforeEach
    void setUp() {
        scheduler = new TickSequenceScheduler(Logger.getLogger("test"));
        callbacks = new RecordingCallbacks();
    }

    private static List<UUID> players(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    private SyncSequence schedule(List<UUID> participants, int countdown, long startTick) {
        return scheduler.schedule(SyncSequence.Mode.SQUAD, UUID.randomUUID(), participants.getFirst(), participants,
                new SequenceTimeline(countdown, 2, ConfigManager.DEFAULT_ORDER), startTick, callbacks);
    }

    private void runTicks(long from, long toInclusive) {
        for (long tick = from; tick <= toInclusive; tick++) {
            scheduler.tick(tick);
        }
    }

    @Test
    void soloStepsRunOnTicksZeroTwoFourSixEightAfterStart() {
        UUID player = UUID.randomUUID();
        scheduler.schedule(SyncSequence.Mode.SOLO, null, player, List.of(player),
                new SequenceTimeline(0, 2, ConfigManager.DEFAULT_ORDER), 100, callbacks);

        runTicks(95, 120);

        List<Long> ticks = callbacks.forParticipant(player).stream().map(RecordingCallbacks.Execution::actual).toList();
        assertEquals(List.of(100L, 102L, 104L, 106L, 108L), ticks);
        List<EquipmentStep> steps = callbacks.forParticipant(player).stream()
                .map(RecordingCallbacks.Execution::step).toList();
        assertEquals(ConfigManager.DEFAULT_ORDER, steps);
        assertEquals(List.of(FinishReason.COMPLETED), callbacks.finishes);
        assertTrue(scheduler.activeSequences().isEmpty());
    }

    @Test
    void everyStepIsOnItsOwnTickNeverSimultaneous() {
        UUID player = UUID.randomUUID();
        schedule(List.of(player), 0, 10);
        runTicks(10, 30);

        Set<Long> distinct = callbacks.executions.stream().map(RecordingCallbacks.Execution::actual)
                .collect(Collectors.toSet());
        assertEquals(5, distinct.size());
    }

    @Test
    void allSquadMembersShareEveryStepTick() {
        List<UUID> squad = players(25);
        schedule(squad, 3, 1000);

        runTicks(1000, 1100);

        assertEquals(25 * 5, callbacks.executions.size());
        long expectedFirst = 1000 + 60;
        for (int i = 0; i < ConfigManager.DEFAULT_ORDER.size(); i++) {
            EquipmentStep step = ConfigManager.DEFAULT_ORDER.get(i);
            Set<Long> ticks = callbacks.forStep(step).stream().map(RecordingCallbacks.Execution::actual)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(expectedFirst + 2L * i), ticks, "all members equip " + step + " on one tick");
            assertEquals(25, callbacks.forStep(step).size());
        }
    }

    @Test
    void timingOffsetsAreIdenticalForEveryParticipant() {
        List<UUID> squad = players(10);
        schedule(squad, 0, 50);
        runTicks(50, 70);

        List<Long> reference = null;
        for (UUID member : squad) {
            List<RecordingCallbacks.Execution> mine = callbacks.forParticipant(member);
            long first = mine.getFirst().actual();
            List<Long> offsets = mine.stream().map(e -> e.actual() - first).toList();
            if (reference == null) {
                reference = offsets;
            }
            assertEquals(List.of(0L, 2L, 4L, 6L, 8L), offsets);
            assertEquals(reference, offsets);
        }
    }

    @Test
    void countdownBeatsAreSharedAndOneSecondApart() {
        List<UUID> squad = players(3);
        schedule(squad, 3, 200);
        runTicks(200, 300);

        assertEquals(List.of(3, 2, 1), callbacks.countdowns.stream().map(RecordingCallbacks.Countdown::seconds).toList());
        assertEquals(List.of(200L, 220L, 240L), callbacks.countdowns.stream().map(RecordingCallbacks.Countdown::tick).toList());
        assertEquals(List.of(260L), callbacks.beforeFirstStep);
    }

    @Test
    void cancellingDuringCountdownEquipsNothing() {
        List<UUID> squad = players(4);
        SyncSequence sequence = schedule(squad, 3, 0);
        runTicks(0, 30);

        sequence.cancel(FinishReason.CANCELLED);
        runTicks(31, 200);

        assertTrue(callbacks.executions.isEmpty());
        assertFalse(sequence.hasEquippingStarted());
        assertEquals(List.of(FinishReason.CANCELLED), callbacks.finishes);
        assertTrue(scheduler.activeSequences().isEmpty());
    }

    @Test
    void cancellingMidwayStopsRemainingSteps() {
        List<UUID> squad = players(2);
        SyncSequence sequence = schedule(squad, 0, 0);
        runTicks(0, 3); // boots (0) and leggings (2) have run

        sequence.cancel(FinishReason.CANCELLED);
        runTicks(4, 20);

        assertEquals(4, callbacks.executions.size());
        assertTrue(sequence.hasEquippingStarted());
        assertEquals(2, sequence.equippedCount(squad.getFirst()));
    }

    @Test
    void removedParticipantGetsNoFurtherStepsWhileOthersContinue() {
        List<UUID> squad = players(3);
        SyncSequence sequence = schedule(squad, 0, 0);
        runTicks(0, 2);

        UUID leaver = squad.get(1);
        assertTrue(scheduler.removeParticipant(leaver, RemovalReason.DISCONNECTED));
        runTicks(3, 20);

        assertEquals(2, callbacks.forParticipant(leaver).size());
        assertEquals(5, callbacks.forParticipant(squad.get(0)).size());
        assertEquals(5, callbacks.forParticipant(squad.get(2)).size());
        assertEquals(List.of(new RecordingCallbacks.Removal(leaver, RemovalReason.DISCONNECTED, 2)), callbacks.removals);
        assertEquals(List.of(FinishReason.COMPLETED), callbacks.finishes);
        assertFalse(sequence.hasParticipant(leaver));
    }

    @Test
    void sequenceEndsWhenEveryoneLeaves() {
        List<UUID> squad = players(2);
        schedule(squad, 3, 0);
        scheduler.removeParticipant(squad.get(0), RemovalReason.DIED);
        scheduler.removeParticipant(squad.get(1), RemovalReason.DISCONNECTED);
        runTicks(0, 100);

        assertTrue(callbacks.executions.isEmpty());
        assertEquals(List.of(FinishReason.NO_PARTICIPANTS), callbacks.finishes);
    }

    @Test
    void abortedStepRemovesOnlyThatParticipant() {
        List<UUID> squad = players(2);
        UUID unlucky = squad.get(0);
        callbacks.outcome = (id, step) -> id.equals(unlucky) && step == EquipmentStep.CHESTPLATE
                ? StepOutcome.ABORTED : StepOutcome.EQUIPPED;
        schedule(squad, 0, 0);
        runTicks(0, 20);

        assertEquals(3, callbacks.forParticipant(unlucky).size());
        assertEquals(5, callbacks.forParticipant(squad.get(1)).size());
        assertEquals(RemovalReason.ITEM_UNAVAILABLE, callbacks.removals.getFirst().reason());
        assertEquals(2, callbacks.removals.getFirst().equipped());
    }

    @Test
    void concurrentSquadsRunIndependentlyAndEachStaysInSync() {
        List<UUID> alpha = players(5);
        List<UUID> bravo = players(7);
        SyncSequence a = schedule(alpha, 0, 10);
        SyncSequence b = schedule(bravo, 1, 13);
        runTicks(0, 100);

        for (SyncSequence sequence : List.of(a, b)) {
            for (EquipmentStep step : ConfigManager.DEFAULT_ORDER) {
                Set<Long> ticks = callbacks.executions.stream()
                        .filter(e -> e.sequenceId() == sequence.id() && e.step() == step)
                        .map(RecordingCallbacks.Execution::actual).collect(Collectors.toSet());
                assertEquals(1, ticks.size(), "sequence #" + sequence.id() + " " + step + " on a single tick");
            }
        }
        assertEquals(10L, firstTick(a, EquipmentStep.BOOTS));
        assertEquals(33L, firstTick(b, EquipmentStep.BOOTS));
        assertEquals(List.of(FinishReason.COMPLETED, FinishReason.COMPLETED), callbacks.finishes);
    }

    private long firstTick(SyncSequence sequence, EquipmentStep step) {
        return callbacks.executions.stream().filter(e -> e.sequenceId() == sequence.id() && e.step() == step)
                .findFirst().orElseThrow().actual();
    }

    @Test
    void lagSpikeShiftsTheRestOfTheTimelineAndPreservesRhythm() {
        List<UUID> squad = players(6);
        SyncSequence sequence = schedule(squad, 0, 0);
        // Boots at tick 0, then the server stalls: the next tick the scheduler sees is 7.
        scheduler.tick(0);
        List<Long> observed = new ArrayList<>(List.of(7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L));
        for (long tick : observed) {
            scheduler.tick(tick);
        }

        for (UUID member : squad) {
            List<Long> ticks = callbacks.forParticipant(member).stream().map(RecordingCallbacks.Execution::actual).toList();
            // Leggings was due at 2 and runs late at 7; the remaining steps keep their 2-tick spacing.
            assertEquals(List.of(0L, 7L, 9L, 11L, 13L), ticks);
        }
        assertEquals(5, sequence.drift());
        for (EquipmentStep step : ConfigManager.DEFAULT_ORDER) {
            assertEquals(1, callbacks.forStep(step).stream().map(RecordingCallbacks.Execution::actual).distinct().count());
        }
    }

    @Test
    void lagNeverCollapsesSeveralStepsIntoOneTick() {
        UUID player = UUID.randomUUID();
        schedule(List.of(player), 0, 0);
        scheduler.tick(0);
        scheduler.tick(50); // every remaining step is overdue
        scheduler.tick(51);
        scheduler.tick(52);

        List<Long> ticks = callbacks.forParticipant(player).stream().map(RecordingCallbacks.Execution::actual).toList();
        assertEquals(List.of(0L, 50L, 52L), ticks, "one step per due tick, spacing kept");
    }

    @Test
    void scheduledTickIsReportedAlongsideActualTick() {
        UUID player = UUID.randomUUID();
        schedule(List.of(player), 0, 0);
        scheduler.tick(0);
        scheduler.tick(5);

        RecordingCallbacks.Execution late = callbacks.executions.get(1);
        assertEquals(2, late.scheduled());
        assertEquals(5, late.actual());
    }

    @Test
    void aPlayerCannotBeInTwoSequences() {
        UUID player = UUID.randomUUID();
        schedule(List.of(player), 0, 0);
        assertThrows(IllegalStateException.class, () -> schedule(List.of(player), 0, 0));
        assertTrue(scheduler.isBusy(player));
    }

    @Test
    void failingCallbackCancelsOnlyThatSequence() {
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        callbacks.outcome = (id, step) -> {
            if (id.equals(bad)) {
                throw new IllegalStateException("boom");
            }
            return StepOutcome.EQUIPPED;
        };
        schedule(List.of(bad), 0, 0);
        schedule(List.of(good), 0, 0);
        runTicks(0, 20);

        assertEquals(5, callbacks.forParticipant(good).size());
        assertTrue(callbacks.finishes.contains(FinishReason.CANCELLED));
        assertTrue(callbacks.finishes.contains(FinishReason.COMPLETED));
        assertTrue(scheduler.activeSequences().isEmpty());
    }

    @Test
    void cancelAllNotifiesEverySequence() {
        schedule(players(2), 3, 0);
        schedule(players(3), 3, 0);
        scheduler.cancelAll(FinishReason.SHUTDOWN);

        assertEquals(List.of(FinishReason.SHUTDOWN, FinishReason.SHUTDOWN), callbacks.finishes);
        assertTrue(scheduler.activeSequences().isEmpty());
    }

    @Test
    void handlesLargeSquadsWithoutArbitraryLimit() {
        List<UUID> army = players(250);
        schedule(army, 0, 0);
        runTicks(0, 20);
        assertEquals(250 * 5, callbacks.executions.size());
        assertEquals(5, callbacks.executions.stream().map(RecordingCallbacks.Execution::actual).distinct().count());
    }
}
