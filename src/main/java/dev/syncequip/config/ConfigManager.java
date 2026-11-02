package dev.syncequip.config;

import dev.syncequip.config.SyncEquipSettings.Controls;
import dev.syncequip.config.SyncEquipSettings.CountdownDisplay;
import dev.syncequip.config.SyncEquipSettings.Demo;
import dev.syncequip.config.SyncEquipSettings.DemoFacing;
import dev.syncequip.config.SyncEquipSettings.HotkeyAction;
import dev.syncequip.config.SyncEquipSettings.Blocks;
import dev.syncequip.config.SyncEquipSettings.Sounds;
import dev.syncequip.config.SyncEquipSettings.Visuals;
import dev.syncequip.equipment.EquipmentStep;
import dev.syncequip.equipment.Loadout;
import dev.syncequip.equipment.MissingItemPolicy;
import dev.syncequip.synchronization.SequenceTimeline;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigManager {

    public static final List<EquipmentStep> DEFAULT_ORDER = List.of(EquipmentStep.BOOTS, EquipmentStep.LEGGINGS,
            EquipmentStep.CHESTPLATE, EquipmentStep.HELMET, EquipmentStep.SHIELD);

    private final JavaPlugin plugin;
    private SyncEquipSettings settings;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public List<String> load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        List<String> warnings = new ArrayList<>();
        settings = parse(plugin.getConfig(), warnings);
        Logger logger = plugin.getLogger();
        for (String warning : warnings) {
            logger.warning("config.yml: " + warning);
        }
        return warnings;
    }

    public SyncEquipSettings settings() {
        return settings;
    }

    /** Invalid values fall back to safe defaults and add a warning, so a bad config never stops the plugin loading. */
    public static SyncEquipSettings parse(ConfigurationSection config, List<String> warnings) {
        int interval = intInRange(config, "equipment.interval-ticks", 2,
                SequenceTimeline.MIN_INTERVAL, SequenceTimeline.MAX_INTERVAL, warnings);
        List<EquipmentStep> order = parseOrder(config, warnings);
        // Occupied slots are always swapped now. The old option is read only to tell the owner it no longer applies.
        boolean requireEmpty = false;
        if (config.getBoolean("equipment.require-empty-slots", false)) {
            warnings.add("equipment.require-empty-slots is no longer used. Worn items and held items are swapped "
                    + "automatically and the old ones go back to the inventory.");
        }
        MissingItemPolicy policy = parseEnum(config, "equipment.missing-item-policy", MissingItemPolicy.class,
                MissingItemPolicy.ABORT, warnings);
        boolean lockInventory = config.getBoolean("equipment.lock-inventory-during-sequence", true);

        int soloCountdown = intInRange(config, "solo.countdown-seconds", 0, 0, 10, warnings);
        int squadCountdown = intInRange(config, "synchronization.countdown-seconds", 3, 0, 10, warnings);
        int maxSquad = intInRange(config, "synchronization.max-squad-size", 30, 2, Integer.MAX_VALUE, warnings);
        if (maxSquad > 200) {
            warnings.add("synchronization.max-squad-size is " + maxSquad
                    + "; very large squads work but every member's sounds are broadcast to nearby players");
        }
        int inviteExpiry = intInRange(config, "synchronization.invite-expiry-seconds", 60, 5, 3600, warnings);
        if (config.isSet("synchronization.shared-tick-scheduler")
                && !config.getBoolean("synchronization.shared-tick-scheduler")) {
            warnings.add("synchronization.shared-tick-scheduler cannot be disabled; all sequences always share "
                    + "one tick scheduler");
        }

        Sounds sounds = new Sounds(
                config.getBoolean("sounds.enabled", true),
                config.getBoolean("sounds.use-vanilla-armor-sounds", true),
                floatInRange(config, "sounds.volume", 1.0f, 0.0f, 4.0f, warnings),
                floatInRange(config, "sounds.pitch", 1.0f, 0.5f, 2.0f, warnings),
                soundKey(config, "sounds.armor-fallback-sound", "minecraft:item.armor.equip_generic", warnings),
                soundKey(config, "sounds.shield.sound", "minecraft:item.shield.block", warnings),
                floatInRange(config, "sounds.shield.volume", 0.8f, 0.0f, 4.0f, warnings),
                floatInRange(config, "sounds.shield.pitch", 1.2f, 0.5f, 2.0f, warnings),
                config.getBoolean("sounds.countdown.enabled", true),
                soundKey(config, "sounds.countdown.sound", "minecraft:block.note_block.hat", warnings),
                config.getBoolean("sounds.go.enabled", false),
                soundKey(config, "sounds.go.sound", "minecraft:block.note_block.snare", warnings),
                config.getBoolean("sounds.completion.enabled", false),
                soundKey(config, "sounds.completion.sound", "minecraft:item.armor.equip_netherite", warnings),
                soundKey(config, "sounds.mainhand.sound", "minecraft:item.armor.equip_chain", warnings),
                floatInRange(config, "sounds.mainhand.volume", 0.8f, 0.0f, 4.0f, warnings),
                floatInRange(config, "sounds.mainhand.pitch", 1.4f, 0.5f, 2.0f, warnings),
                floatInRange(config, "sounds.unequip-pitch-multiplier", 0.8f, 0.25f, 2.0f, warnings));

        Visuals visuals = new Visuals(
                config.getBoolean("visuals.particles-enabled", false),
                config.getBoolean("visuals.actionbar-enabled", true),
                parseEnum(config, "visuals.countdown-display", CountdownDisplay.class, CountdownDisplay.TITLE,
                        warnings));

        Controls controls = new Controls(
                config.getBoolean("controls.sneak-swap-trigger", false),
                parseEnum(config, "controls.sneak-swap-action", HotkeyAction.class, HotkeyAction.SOLO, warnings),
                intInRange(config, "controls.cooldown-seconds", 3, 0, 3600, warnings));

        Demo demo = new Demo(
                intInRange(config, "demo.interval-ticks", 4, SequenceTimeline.MIN_INTERVAL,
                        SequenceTimeline.MAX_INTERVAL, warnings),
                intInRange(config, "demo.countdown-seconds", 3, 0, 10, warnings),
                intInRange(config, "demo.repeat-hold-seconds", 3, 0, 60, warnings),
                intInRange(config, "demo.max-mobs", 20, 1, 100, warnings),
                floatInRange(config, "demo.spacing", 1.5f, 1.0f, 5.0f, warnings),
                floatInRange(config, "demo.distance", 4.0f, 2.0f, 20.0f, warnings),
                parseEnum(config, "demo.facing", DemoFacing.class, DemoFacing.TOWARD_PLAYER, warnings));

        Loadout defaultLoadout = Loadout.load(config.getConfigurationSection("loadout.default"),
                Loadout.standard().withOrder(order), "loadout.default", warnings);
        Blocks blocks = new Blocks(config.getBoolean("blocks.enabled", config.getBoolean("pads.enabled", true)),
                intInRange(config, config.isSet("blocks.notice-cooldown-seconds") ? "blocks.notice-cooldown-seconds"
                        : "pads.notice-cooldown-seconds", 5, 0, 600, warnings),
                config.getBoolean("blocks.all-worlds", false));

        return new SyncEquipSettings(interval, order, requireEmpty, policy, lockInventory, soloCountdown,
                squadCountdown, maxSquad, inviteExpiry, sounds, visuals, controls, demo, defaultLoadout,
                config.getBoolean("loadout.solo-uses-default", false),
                intInRange(config, "synchronization.unequip-countdown-seconds", 3, 0, 10, warnings), blocks,
                config.getBoolean("debug.log-sequence-ticks", false));
    }

    public static SyncEquipSettings defaults() {
        return parse(new YamlConfiguration(), new ArrayList<>());
    }

    private static List<EquipmentStep> parseOrder(ConfigurationSection config, List<String> warnings) {
        if (!config.isList("equipment.order")) {
            if (config.isSet("equipment.order")) {
                warnings.add("equipment.order must be a list; using the default order");
            }
            return DEFAULT_ORDER;
        }
        List<EquipmentStep> order = new ArrayList<>();
        Set<EquipmentStep> seen = EnumSet.noneOf(EquipmentStep.class);
        for (String raw : config.getStringList("equipment.order")) {
            EquipmentStep step = EquipmentStep.parse(raw).orElse(null);
            if (step == null) {
                warnings.add("equipment.order: unknown step '" + raw + "' ignored (valid: BOOTS, LEGGINGS, "
                        + "CHESTPLATE, HELMET, SHIELD or OFFHAND, MAIN_HAND)");
            } else if (!seen.add(step)) {
                warnings.add("equipment.order: duplicate step " + step + " ignored");
            } else {
                order.add(step);
            }
        }
        if (order.isEmpty()) {
            warnings.add("equipment.order has no valid steps; using the default order");
            return DEFAULT_ORDER;
        }
        return order;
    }

    private static int intInRange(ConfigurationSection config, String path, int def, int min, int max,
                                  List<String> warnings) {
        if (!config.isSet(path)) {
            return def;
        }
        if (!config.isInt(path)) {
            warnings.add(path + " must be a whole number; using " + def);
            return def;
        }
        int value = config.getInt(path);
        if (value < min || value > max) {
            int clamped = Math.max(min, Math.min(max, value));
            warnings.add(path + " = " + value + " is out of range [" + min + ", "
                    + (max == Integer.MAX_VALUE ? "∞" : max) + "]; using " + clamped);
            return clamped;
        }
        return value;
    }

    private static float floatInRange(ConfigurationSection config, String path, float def, float min, float max,
                                      List<String> warnings) {
        if (!config.isSet(path)) {
            return def;
        }
        if (!(config.isDouble(path) || config.isInt(path))) {
            warnings.add(path + " must be a number; using " + def);
            return def;
        }
        float value = (float) config.getDouble(path);
        if (value < min || value > max) {
            float clamped = Math.max(min, Math.min(max, value));
            warnings.add(path + " = " + value + " is out of range [" + min + ", " + max + "]; using " + clamped);
            return clamped;
        }
        return value;
    }

    private static <E extends Enum<E>> E parseEnum(ConfigurationSection config, String path, Class<E> type,
                                                   E def, List<String> warnings) {
        String raw = config.getString(path);
        if (raw == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warnings.add(path + ": unknown value '" + raw + "'; using " + def);
            return def;
        }
    }

    private static String soundKey(ConfigurationSection config, String path, String def, List<String> warnings) {
        String raw = config.getString(path, def);
        try {
            return Key.key(raw.trim().toLowerCase(Locale.ROOT)).asString();
        } catch (InvalidKeyException e) {
            warnings.add(path + ": '" + raw + "' is not a valid sound key; using " + def);
            return def;
        }
    }
}
