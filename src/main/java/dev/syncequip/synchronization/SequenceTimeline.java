package dev.syncequip.synchronization;

import dev.syncequip.equipment.EquipmentStep;
import java.util.ArrayList;
import java.util.List;

public final class SequenceTimeline {
    public static final int TICKS_PER_SECOND = 20;
    public static final int MIN_INTERVAL = 1;
    public static final int MAX_INTERVAL = 10;

    private final int countdownSeconds;
    private final int intervalTicks;
    private final List<EquipmentStep> steps;
    private final List<TimelineEvent> events;

    public SequenceTimeline(int countdownSeconds, int intervalTicks, List<EquipmentStep> steps) {
        if (countdownSeconds < 0) {
            throw new IllegalArgumentException("countdownSeconds must be >= 0");
        }
        if (intervalTicks < MIN_INTERVAL || intervalTicks > MAX_INTERVAL) {
            throw new IllegalArgumentException("intervalTicks must be between 1 and 10");
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        this.countdownSeconds = countdownSeconds;
        this.intervalTicks = intervalTicks;
        this.steps = List.copyOf(steps);
        this.events = buildEvents();
    }

    private List<TimelineEvent> buildEvents() {
        List<TimelineEvent> list = new ArrayList<>();
        for (int i = 0; i < countdownSeconds; i++) {
            list.add(new TimelineEvent(TimelineEvent.Type.COUNTDOWN, countdownSeconds - i, null,
                    (long) i * TICKS_PER_SECOND));
        }
        for (int i = 0; i < steps.size(); i++) {
            list.add(new TimelineEvent(TimelineEvent.Type.STEP, i, steps.get(i), stepOffset(i)));
        }
        list.add(new TimelineEvent(TimelineEvent.Type.FINISH, 0, null,
                stepOffset(steps.size() - 1) + intervalTicks));
        return List.copyOf(list);
    }

    public long firstStepOffset() {
        return (long) countdownSeconds * TICKS_PER_SECOND;
    }

    public long stepOffset(int stepIndex) {
        return firstStepOffset() + (long) stepIndex * intervalTicks;
    }

    public int countdownSeconds() {
        return countdownSeconds;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public List<EquipmentStep> steps() {
        return steps;
    }

    public List<TimelineEvent> events() {
        return events;
    }
}
