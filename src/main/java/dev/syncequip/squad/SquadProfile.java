package dev.syncequip.squad;

import dev.syncequip.equipment.Loadout;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;

/**
 * The settings a squad keeps: its name, who owns it, its own speed and loadout, and its joining block. Unlike the
 * list of members, this survives the squad being empty and a server restart. A {@code null} speed or loadout means
 * "use the global default from config.yml".
 */
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

    /** The block material whose blocks add players to this squad, or {@code null} for none. */
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

    /**
     * A description of an old coordinate based joining block found in a saved file. It is never used to add anyone to
     * a squad. It is only kept so the leader can be told once that it was retired.
     */
    public String legacyPad() {
        return legacyPad;
    }

    /** Whether there is anything worth saving to disk. A plain squad with no overrides is not saved. */
    public boolean persistable() {
        return speed != null || loadout != null || blockMaterial != null || legacyPad != null || !members.isEmpty();
    }

    /** The members of the live squad, in the order they joined. The squad and the saved file share this one set. */
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
