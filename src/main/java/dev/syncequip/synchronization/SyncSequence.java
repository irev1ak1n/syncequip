package dev.syncequip.synchronization;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One running equipment sequence shared by one or more participants.
 *
 * <p>Every participant shares a single timeline and a single start tick. When an event becomes due,
 * it is executed for <em>all</em> participants inside the same {@link #advance(long)} call, so the
 * corresponding step is guaranteed to land on the same server tick for everyone.
 *
 * <p>Lag handling: if the driver calls {@link #advance(long)} late (e.g. the server skipped ahead),
 * the late event runs immediately and the remaining timeline is shifted by the same amount. At most
 * one tick offset is processed per call, so a lag spike never collapses several beats into one and
 * the rhythm between steps is preserved.
 */
public final class SyncSequence {

    public enum Mode {
        SOLO,
        SQUAD
    }

    public enum State {
        PENDING,
        COUNTDOWN,
        RUNNING,
        FINISHED
    }

    private final long id;
    private final Mode mode;
    private final UUID squadId;
    private final UUID initiator;
    private final SequenceTimeline timeline;
    private final long startTick;
    private final SequenceCallbacks callbacks;
    private final Map<UUID, Integer> participants = new LinkedHashMap<>();

    private int cursor;
    private long drift;
    private State state = State.PENDING;
    private boolean equippingStarted;
    private FinishReason finishReason;

    SyncSequence(long id, Mode mode, UUID squadId, UUID initiator, Collection<UUID> participants,
                 SequenceTimeline timeline, long startTick, SequenceCallbacks callbacks) {
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("a sequence needs at least one participant");
        }
        this.id = id;
        this.mode = mode;
        this.squadId = squadId;
        this.initiator = initiator;
        this.timeline = timeline;
        this.startTick = startTick;
        this.callbacks = callbacks;
        for (UUID participant : participants) {
            this.participants.put(participant, 0);
        }
    }

    /**
     * Advances the timeline to {@code now}.
     *
     * @return {@code true} once the sequence has finished and can be discarded
     */
    public boolean advance(long now) {
        if (state == State.FINISHED) {
            return true;
        }
        List<TimelineEvent> events = timeline.events();
        if (cursor >= events.size()) {
            finish(FinishReason.COMPLETED);
            return true;
        }
        TimelineEvent next = events.get(cursor);
        long due = startTick + next.offset() + drift;
        if (now < due) {
            return false;
        }
        drift += now - due;

        long offset = next.offset();
        while (cursor < events.size() && events.get(cursor).offset() == offset && state != State.FINISHED) {
            process(events.get(cursor++), now);
        }
        if (state != State.FINISHED && cursor >= events.size()) {
            finish(FinishReason.COMPLETED);
        }
        return state == State.FINISHED;
    }

    private void process(TimelineEvent event, long now) {
        switch (event.type()) {
            case COUNTDOWN -> {
                state = State.COUNTDOWN;
                callbacks.onCountdown(this, event.index(), now);
            }
            case STEP -> runStep(event, now);
            case FINISH -> finish(FinishReason.COMPLETED);
        }
    }

    private void runStep(TimelineEvent event, long now) {
        if (event.index() == 0) {
            state = State.RUNNING;
            equippingStarted = true;
            callbacks.onBeforeFirstStep(this, now);
            if (state == State.FINISHED) {
                return;
            }
        }
        long scheduled = startTick + event.offset();
        for (UUID participant : List.copyOf(participants.keySet())) {
            if (state == State.FINISHED) {
                return;
            }
            if (!participants.containsKey(participant)) {
                continue;
            }
            StepOutcome outcome = callbacks.executeStep(this, participant, event.step(), event.index(),
                    scheduled, now);
            if (outcome == StepOutcome.EQUIPPED) {
                participants.computeIfPresent(participant, (id, count) -> count + 1);
            } else if (outcome == StepOutcome.ABORTED) {
                removeParticipant(participant, RemovalReason.ITEM_UNAVAILABLE);
            }
        }
        if (state != State.FINISHED) {
            callbacks.afterStep(this, event.step(), event.index(), now);
        }
    }

    /**
     * Removes a participant. Pieces they already equipped stay equipped; nothing is rolled back.
     *
     * @return whether the participant was part of this (unfinished) sequence
     */
    public boolean removeParticipant(UUID participant, RemovalReason reason) {
        if (state == State.FINISHED) {
            return false;
        }
        Integer equipped = participants.remove(participant);
        if (equipped == null) {
            return false;
        }
        callbacks.onParticipantRemoved(this, participant, reason, equipped);
        if (participants.isEmpty()) {
            finish(FinishReason.NO_PARTICIPANTS);
        }
        return true;
    }

    public void cancel(FinishReason reason) {
        finish(reason);
    }

    private void finish(FinishReason reason) {
        if (state == State.FINISHED) {
            return;
        }
        state = State.FINISHED;
        finishReason = reason;
        callbacks.onFinished(this, reason);
    }

    public long id() {
        return id;
    }

    public Mode mode() {
        return mode;
    }

    /** The squad this sequence belongs to, or {@code null} for solo sequences. */
    public UUID squadId() {
        return squadId;
    }

    public UUID initiator() {
        return initiator;
    }

    public SequenceTimeline timeline() {
        return timeline;
    }

    /** The shared server tick at which the timeline (offset 0) begins. */
    public long startTick() {
        return startTick;
    }

    /** Ticks the timeline has been pushed back by late execution. */
    public long drift() {
        return drift;
    }

    public State state() {
        return state;
    }

    public boolean isFinished() {
        return state == State.FINISHED;
    }

    /** Whether equipment has started changing (the first step was reached). */
    public boolean hasEquippingStarted() {
        return equippingStarted;
    }

    public FinishReason finishReason() {
        return finishReason;
    }

    public Set<UUID> participants() {
        return Collections.unmodifiableSet(participants.keySet());
    }

    public boolean hasParticipant(UUID participant) {
        return participants.containsKey(participant);
    }

    public int equippedCount(UUID participant) {
        return participants.getOrDefault(participant, 0);
    }
}
