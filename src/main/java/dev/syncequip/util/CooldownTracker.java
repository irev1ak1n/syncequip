package dev.syncequip.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class CooldownTracker {
    private final LongSupplier clock;
    private final Map<UUID, Long> readyAt = new HashMap<>();

    public CooldownTracker(LongSupplier clock) {
        this.clock = clock;
    }

    public void start(UUID player, int seconds) {
        if (seconds <= 0) {
            readyAt.remove(player);
            return;
        }
        readyAt.put(player, clock.getAsLong() + seconds * 1000L);
    }

    public long remainingMillis(UUID player) {
        Long until = readyAt.get(player);
        if (until == null) {
            return 0;
        }
        long remaining = until - clock.getAsLong();
        if (remaining <= 0) {
            readyAt.remove(player);
            return 0;
        }
        return remaining;
    }

    public int remainingSeconds(UUID player) {
        return (int) ((remainingMillis(player) + 999) / 1000);
    }

    public boolean isCoolingDown(UUID player) {
        return remainingMillis(player) > 0;
    }

    public void clear(UUID player) {
        readyAt.remove(player);
    }
}
