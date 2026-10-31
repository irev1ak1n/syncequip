package dev.syncequip.synchronization;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        // If the server runs late, the due event runs at once and later events shift by the same delay. Spacing between steps stays the same.
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

    public UUID squadId() {
        return squadId;
    }

    public UUID initiator() {
        return initiator;
    }

    public SequenceTimeline timeline() {
        return timeline;
    }

    public long startTick() {
        return startTick;
    }

    public long drift() {
        return drift;
    }

    public State state() {
        return state;
    }

    public boolean isFinished() {
        return state == State.FINISHED;
    }

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
