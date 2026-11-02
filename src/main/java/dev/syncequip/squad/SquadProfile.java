package dev.syncequip.squad;

import dev.syncequip.equipment.Loadout;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;

/** A squad's persistent settings. A {@code null} speed or loadout means the config.yml default. */
public final class SquadProfile {
    private final String name;
    private UUID owner;
    private final Set<UUID> members = new LinkedHashSet<>();
    private Integer speed;
    private Loadout loadout;
    private Material blockMaterial;
    private String blockWorldName;
    private UUID blockWorldId;
    private boolean blockEnabled = true;
    private String legacyPad;

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

    public UUID owner() {
        return owner;
    }

    public Integer speed() {
        return speed;
    }

    public Loadout loadout() {
        return loadout;
    }

    public Material blockMaterial() {
        return blockMaterial;
    }

    public String blockWorldName() {
        return blockWorldName;
    }

    public UUID blockWorldId() {
        return blockWorldId;
    }

    public boolean blockEnabled() {
        return blockEnabled;
    }

    public String legacyPad() {
        return legacyPad;
    }

    public boolean persistable() {
        return speed != null || loadout != null || blockMaterial != null || legacyPad != null || !members.isEmpty();
    }

    Set<UUID> memberSet() {
        return members;
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

    void setBlock(Material material, String worldName, UUID worldId) {
        this.blockMaterial = material;
        this.blockWorldName = worldName;
        this.blockWorldId = worldId;
    }

    void clearBlock() {
        this.blockMaterial = null;
        this.blockWorldName = null;
        this.blockWorldId = null;
        this.blockEnabled = true;
    }

    void setBlockEnabled(boolean blockEnabled) {
        this.blockEnabled = blockEnabled;
    }

    void setLegacyPad(String legacyPad) {
        this.legacyPad = legacyPad;
    }
}
