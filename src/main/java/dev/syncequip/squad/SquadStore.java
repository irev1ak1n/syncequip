package dev.syncequip.squad;

import dev.syncequip.equipment.Loadout;
import dev.syncequip.synchronization.SequenceTimeline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads and writes {@code squads.yml}. Only what is needed to bring a squad back is stored: its name, the owner's
 * unique id, its speed and loadout, and its joining block as a world and three numbers. No names, addresses or
 * other player details are saved, and neither are the members.
 */
public final class SquadStore {

    private final Path file;
    private final Logger logger;

    public SquadStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Reads every saved squad. Entries that cannot be read are skipped and reported in {@code warnings}. */
    public List<SquadProfile> load(List<String> warnings) {
        List<SquadProfile> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            warnings.add("squads.yml could not be read (" + e.getMessage() + "). Saved squads were not loaded.");
            return result;
        }
        ConfigurationSection squads = yaml.getConfigurationSection("squads");
        if (squads == null) {
            return result;
        }
        for (String key : squads.getKeys(false)) {
            ConfigurationSection section = squads.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String name = section.getString("name", key);
            UUID owner = parseUuid(section.getString("owner"));
            if (!SquadManager.NAME_PATTERN.matcher(name).matches() || owner == null) {
                warnings.add("squads.yml: skipped '" + key + "' (missing or invalid name or owner)");
                continue;
            }
            SquadProfile profile = new SquadProfile(name, owner);
            if (section.isInt("speed")) {
                int speed = section.getInt("speed");
                if (speed >= SequenceTimeline.MIN_INTERVAL && speed <= SequenceTimeline.MAX_INTERVAL) {
                    profile.setSpeed(speed);
                } else {
                    warnings.add("squads.yml: " + name + " speed " + speed + " is out of range and was ignored");
                }
            }
            if (section.isConfigurationSection("loadout")) {
                profile.setLoadout(Loadout.load(section.getConfigurationSection("loadout"), Loadout.standard(),
                        "squads.yml " + name + ".loadout", warnings));
            }
            ConfigurationSection pad = section.getConfigurationSection("pad");
            if (pad != null && pad.isString("world") && pad.isInt("x") && pad.isInt("y") && pad.isInt("z")) {
                profile.setPad(new Pad(parseUuid(pad.getString("world-id")), pad.getString("world"),
                        pad.getInt("x"), pad.getInt("y"), pad.getInt("z")));
                profile.setPadEnabled(pad.getBoolean("enabled", true));
            } else if (pad != null) {
                warnings.add("squads.yml: " + name + " has an incomplete pad and it was ignored");
            }
            result.add(profile);
        }
        return result;
    }

    /** Builds the file contents on the calling thread so the actual write can happen elsewhere. */
    public String serialize(Collection<SquadProfile> profiles) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        for (SquadProfile profile : profiles) {
            if (!profile.persistable()) {
                continue;
            }
            String base = "squads." + profile.key();
            yaml.set(base + ".name", profile.name());
            yaml.set(base + ".owner", profile.owner().toString());
            if (profile.speed() != null) {
                yaml.set(base + ".speed", profile.speed());
            }
            if (profile.loadout() != null) {
                profile.loadout().save(yaml.createSection(base + ".loadout"));
            }
            Pad pad = profile.pad();
            if (pad != null) {
                yaml.set(base + ".pad.world", pad.worldName());
                if (pad.worldId() != null) {
                    yaml.set(base + ".pad.world-id", pad.worldId().toString());
                }
                yaml.set(base + ".pad.x", pad.x());
                yaml.set(base + ".pad.y", pad.y());
                yaml.set(base + ".pad.z", pad.z());
                yaml.set(base + ".pad.enabled", profile.padEnabled());
            }
        }
        return yaml.saveToString();
    }

    /** Writes through a temporary file so a crash never leaves a half written squads.yml. */
    public synchronized void write(String contents) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save squads.yml", e);
        }
    }

    public boolean exists() {
        return Files.exists(file);
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
