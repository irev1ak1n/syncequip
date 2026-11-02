package dev.syncequip.synchronization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.syncequip.config.ConfigManager;
import dev.syncequip.equipment.EquipmentStep;
import java.util.List;
import org.junit.jupiter.api.Test;

class SequenceTimelineTest {
    @Test
    void defaultStepsAreAtOffsetsZeroTwoFourSixEight() {
        SequenceTimeline timeline = new SequenceTimeline(0, 2, ConfigManager.DEFAULT_ORDER);

        List<Long> stepOffsets = timeline.events().stream()
                .filter(e -> e.type() == TimelineEvent.Type.STEP)
                .map(TimelineEvent::offset)
                .toList();
        List<EquipmentStep> stepOrder = timeline.events().stream()
                .filter(e -> e.type() == TimelineEvent.Type.STEP)
                .map(TimelineEvent::step)
                .toList();

        assertEquals(List.of(0L, 2L, 4L, 6L, 8L), stepOffsets);
        assertEquals(List.of(EquipmentStep.BOOTS, EquipmentStep.LEGGINGS, EquipmentStep.CHESTPLATE,
                EquipmentStep.HELMET, EquipmentStep.SHIELD), stepOrder);
    }

    @Test
    void countdownPrecedesStepsInWholeSeconds() {
        SequenceTimeline timeline = new SequenceTimeline(3, 2, ConfigManager.DEFAULT_ORDER);

        List<TimelineEvent> countdown = timeline.events().stream()
                .filter(e -> e.type() == TimelineEvent.Type.COUNTDOWN).toList();
        assertEquals(List.of(3, 2, 1), countdown.stream().map(TimelineEvent::index).toList());
        assertEquals(List.of(0L, 20L, 40L), countdown.stream().map(TimelineEvent::offset).toList());
        assertEquals(60, timeline.firstStepOffset());
        assertEquals(68, timeline.stepOffset(4));
    }

    @Test
    void customIntervalAndOrder() {
        SequenceTimeline timeline = new SequenceTimeline(0, 5,
                List.of(EquipmentStep.HELMET, EquipmentStep.BOOTS));

        assertEquals(0, timeline.stepOffset(0));
        assertEquals(5, timeline.stepOffset(1));
        TimelineEvent finish = timeline.events().getLast();
        assertEquals(TimelineEvent.Type.FINISH, finish.type());
        assertEquals(10, finish.offset());
    }

    @Test
    void rejectsIntervalsOutsideOneToTen() {
        assertThrows(IllegalArgumentException.class, () -> new SequenceTimeline(0, 0, ConfigManager.DEFAULT_ORDER));
        assertThrows(IllegalArgumentException.class, () -> new SequenceTimeline(0, 11, ConfigManager.DEFAULT_ORDER));
        assertThrows(IllegalArgumentException.class, () -> new SequenceTimeline(0, 2, List.of()));
    }
}
