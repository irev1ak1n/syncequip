package dev.syncequip.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CooldownTrackerTest {

    private final AtomicLong clock = new AtomicLong(0);
    private final CooldownTracker cooldowns = new CooldownTracker(clock::get);
    private final UUID player = UUID.randomUUID();

    @Test
    void cooldownExpires() {
        cooldowns.start(player, 3);
        assertTrue(cooldowns.isCoolingDown(player));
        assertEquals(3, cooldowns.remainingSeconds(player));

        clock.set(2_001);
        assertEquals(1, cooldowns.remainingSeconds(player));

        clock.set(3_000);
        assertFalse(cooldowns.isCoolingDown(player));
    }

    @Test
    void zeroSecondsMeansNoCooldown() {
        cooldowns.start(player, 0);
        assertFalse(cooldowns.isCoolingDown(player));
    }

    @Test
    void clearRemovesCooldown() {
        cooldowns.start(player, 10);
        cooldowns.clear(player);
        assertFalse(cooldowns.isCoolingDown(player));
    }
}
