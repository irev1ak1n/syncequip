package dev.syncequip.synchronization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single, shared scheduler for every equipment sequence on the server.
 *
 * <p>It is driven by exactly one repeating server task that calls {@link #tick(long)} once per tick
 * with the current server tick. Solo and squad sequences, and any number of concurrent squads, all
 * advance from that one loop, so there are no per-player timers that could drift apart.
 *
 * <p>This class has no Bukkit dependency, which keeps the timing logic unit-testable.
 */
public final class TickSequenceScheduler {

    private final Logger logger;
    private final List<SyncSequence> active = new ArrayList<>();
    private long nextId = 1;

    public TickSequenceScheduler(Logger logger) {
        this.logger = logger;
    }

    /**
     * Registers a new sequence.
     *
     * @param startTick the shared server tick at which the timeline begins; must be in the future
     *                  relative to the tick that will next call {@link #tick(long)}
     * @throws IllegalStateException if any participant is already in an active sequence
     */
    public SyncSequence schedule(SyncSequence.Mode mode, UUID squadId, UUID initiator,
                                 Collection<UUID> participants, SequenceTimeline timeline, long startTick,
                                 SequenceCallbacks callbacks) {
        for (UUID participant : participants) {
            if (isBusy(participant)) {
                throw new IllegalStateException("participant " + participant + " is already in a sequence");
            }
        }
        SyncSequence sequence = new SyncSequence(nextId++, mode, squadId, initiator, participants, timeline,
                startTick, callbacks);
        active.add(sequence);
        return sequence;
    }

    /** Advances every active sequence to {@code now}. Called once per server tick. */
    public void tick(long now) {
        if (active.isEmpty()) {
            return;
        }
        for (SyncSequence sequence : List.copyOf(active)) {
            try {
                sequence.advance(now);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Sequence #" + sequence.id() + " failed; cancelling it", e);
                try {
                    sequence.cancel(FinishReason.CANCELLED);
                } catch (RuntimeException nested) {
                    logger.log(Level.SEVERE, "Failed to clean up sequence #" + sequence.id(), nested);
                }
            }
        }
        active.removeIf(SyncSequence::isFinished);
    }

    public Optional<SyncSequence> findByParticipant(UUID participant) {
        return active.stream().filter(s -> !s.isFinished() && s.hasParticipant(participant)).findFirst();
    }

    public Optional<SyncSequence> findBySquad(UUID squadId) {
        return active.stream().filter(s -> !s.isFinished() && squadId.equals(s.squadId())).findFirst();
    }

    public boolean isBusy(UUID participant) {
        return findByParticipant(participant).isPresent();
    }

    /** Removes a participant from whatever sequence they are in. */
    public boolean removeParticipant(UUID participant, RemovalReason reason) {
        return findByParticipant(participant).map(s -> s.removeParticipant(participant, reason)).orElse(false);
    }

    /** Ends every running sequence, e.g. when the plugin is disabled. */
    public void cancelAll(FinishReason reason) {
        for (SyncSequence sequence : List.copyOf(active)) {
            sequence.cancel(reason);
        }
        active.clear();
    }

    public List<SyncSequence> activeSequences() {
        return List.copyOf(active);
    }
}
