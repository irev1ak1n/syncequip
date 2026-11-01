package dev.syncequip.squad;

import dev.syncequip.equipment.Loadout;
import java.util.Locale;
import java.util.UUID;

/**
 * The settings a squad keeps: its name, who owns it, its own speed and loadout, and its joining block. Unlike
 * the list of members, this survives the squad being empty and a server restart. A {@code null} speed or loadout
 * means "use the global default from config.yml".
 */
public final class SquadProfile {

    private final String name;
    private UUID owner;
    private Integer speed;
    private Loadout loadout;
    private Pad pad;
    private boolean padEnabled = true;

    public SquadProfile(String name, UUID owner) {
        this.name = name;
        this.owner = owner;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public String name() {
        return name;
    }

    public String key() {
        return key(name);
    }

    /** The player who last led this squad. Only this player (or an admin) can bring the saved squad back. */
    public UUID owner() {
        return owner;
    }

    public Integer speed() {
        return speed;
    }

    public Loadout loadout() {
        return loadout;
    }

    public Pad pad() {
        return pad;
    }

    public boolean padEnabled() {
        return padEnabled;
    }

    /** Whether there is anything worth saving to disk. A plain squad with no overrides is not saved. */
    public boolean persistable() {
        return speed != null || loadout != null || pad != null;
    }

    void setOwner(UUID owner) {
        this.owner = owner;
    }

    void setSpeed(Integer speed) {
        this.speed = speed;
    }

    void setLoadout(Loadout loadout) {
        this.loadout = loadout;
    }

    void setPad(Pad pad) {
        this.pad = pad;
    }

    void setPadEnabled(boolean padEnabled) {
        this.padEnabled = padEnabled;
    }
}
