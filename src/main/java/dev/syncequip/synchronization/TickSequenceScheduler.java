package dev.syncequip.synchronization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** One shared scheduler advances every sequence, so no player gets a private timer that could drift away from the others. */
public final class TickSequenceScheduler {

    private final Logger logger;
    private final List<SyncSequence> active = new ArrayList<>();
    private long nextId = 1;

    public TickSequenceScheduler(Logger logger) {
        this.logger = logger;
    }

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

    public boolean removeParticipant(UUID participant, RemovalReason reason) {
        return findByParticipant(participant).map(s -> s.removeParticipant(participant, reason)).orElse(false);
    }

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
