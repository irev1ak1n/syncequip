package dev.syncequip.squad;

import java.util.UUID;

/**
 * One exact block that adds players to a squad when they step on it. Only the world and the block's
 * coordinates are stored.
 *
 * @param worldId   unique id of the world, the preferred way to find it again
 * @param worldName name of the world, used if the id no longer matches (for example a recreated world)
 */
public record Pad(UUID worldId, String worldName, int x, int y, int z) {

    /** Packs the three coordinates into one number so lookups while players walk allocate nothing. */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    public long key() {
        return key(x, y, z);
    }
}
