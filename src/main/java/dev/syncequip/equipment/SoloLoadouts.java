package dev.syncequip.equipment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Each player's own loadout for {@code /syncequip solo}, saved in {@code solo-loadouts.yml}. */
public final class SoloLoadouts {
    private final Path file;
    private final Logger logger;
    private final Map<UUID, Loadout> loadouts = new HashMap<>();
    private Runnable changeListener = () -> { };

    public SoloLoadouts(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    public Optional<Loadout> get(UUID player) {
        return Optional.ofNullable(loadouts.get(player));
    }

    public void set(UUID player, Loadout loadout) {
        loadouts.put(player, loadout);
        changeListener.run();
    }

    public boolean reset(UUID player) {
        boolean had = loadouts.remove(player) != null;
        if (had) {
            changeListener.run();
        }
        return had;
    }

    public void load(List<String> warnings) {
        loadouts.clear();
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            warnings.add("solo-loadouts.yml could not be read (" + e.getMessage() + "). Personal loadouts were not loaded.");
            return;
        }
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID id = parse(key);
            ConfigurationSection section = players.getConfigurationSection(key + ".loadout");
            if (id == null || section == null) {
                warnings.add("solo-loadouts.yml: skipped '" + key + "' (not a player id or has no loadout)");
                continue;
            }
            loadouts.put(id, Loadout.load(section, Loadout.standard(), "solo-loadouts.yml " + key, warnings));
        }
    }

    public String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        for (Map.Entry<UUID, Loadout> entry : new ArrayList<>(loadouts.entrySet())) {
            entry.getValue().save(yaml.createSection("players." + entry.getKey() + ".loadout"));
        }
        return yaml.saveToString();
    }

    public boolean isEmpty() {
        return loadouts.isEmpty();
    }

    public boolean exists() {
        return Files.exists(file);
    }

    public synchronized void write(String contents) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save solo-loadouts.yml", e);
        }
    }

    private static UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
