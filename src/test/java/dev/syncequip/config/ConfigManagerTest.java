package dev.syncequip.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.equipment.EquipmentStep;
import dev.syncequip.equipment.MissingItemPolicy;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigManagerTest {

    private static SyncEquipSettings parse(String yaml, List<String> warnings) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return ConfigManager.parse(config, warnings);
    }

    @Test
    void bundledConfigParsesWithoutWarnings() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        List<String> warnings = new ArrayList<>();
        SyncEquipSettings settings = ConfigManager.parse(config, warnings);

        assertEquals(List.of(), warnings);
        assertEquals(2, settings.intervalTicks());
        assertEquals(ConfigManager.DEFAULT_ORDER, settings.order());
        assertTrue(settings.requireEmptySlots());
        assertEquals(MissingItemPolicy.ABORT, settings.missingItemPolicy());
        assertEquals(3, settings.squadCountdownSeconds());
        assertEquals(30, settings.maxSquadSize());
        assertTrue(settings.sounds().enabled());
        assertFalse(settings.controls().sneakSwapTrigger());
        assertEquals(3, settings.controls().cooldownSeconds());
    }

    @Test
    void emptyConfigUsesDefaults() {
        SyncEquipSettings settings = ConfigManager.defaults();
        assertEquals(2, settings.intervalTicks());
        assertEquals(ConfigManager.DEFAULT_ORDER, settings.order());
    }

    @Test
    void intervalIsClampedToOneToTen() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals(10, parse("equipment:\n  interval-ticks: 50\n", warnings).intervalTicks());
        assertEquals(1, parse("equipment:\n  interval-ticks: 0\n", warnings).intervalTicks());
        assertEquals(2, parse("equipment:\n  interval-ticks: fast\n", warnings).intervalTicks());
        assertEquals(3, warnings.size());
    }

    @Test
    void customOrderDropsUnknownAndDuplicateSteps() throws Exception {
        List<String> warnings = new ArrayList<>();
        SyncEquipSettings settings = parse("""
                equipment:
                  order: [helmet, BOOTS, gloves, HELMET, shield]
                """, warnings);
        assertEquals(List.of(EquipmentStep.HELMET, EquipmentStep.BOOTS, EquipmentStep.SHIELD), settings.order());
        assertEquals(2, warnings.size());
    }

    @Test
    void emptyOrderFallsBackToDefault() throws Exception {
        List<String> warnings = new ArrayList<>();
        SyncEquipSettings settings = parse("equipment:\n  order: [nope]\n", warnings);
        assertEquals(ConfigManager.DEFAULT_ORDER, settings.order());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void invalidEnumsAndRangesWarn() throws Exception {
        List<String> warnings = new ArrayList<>();
        SyncEquipSettings settings = parse("""
                equipment:
                  missing-item-policy: explode
                synchronization:
                  max-squad-size: 1
                  shared-tick-scheduler: false
                sounds:
                  volume: 99
                  shield:
                    sound: "Not A Key!"
                visuals:
                  countdown-display: hologram
                """, warnings);
        assertEquals(MissingItemPolicy.ABORT, settings.missingItemPolicy());
        assertEquals(2, settings.maxSquadSize());
        assertEquals(4.0f, settings.sounds().volume());
        assertEquals("minecraft:item.shield.block", settings.sounds().shieldSound());
        assertEquals(SyncEquipSettings.CountdownDisplay.TITLE, settings.visuals().countdownDisplay());
        assertEquals(6, warnings.size(), String.join("\n", warnings));
    }

    @Test
    void skipPolicyAndLargeSquads() throws Exception {
        List<String> warnings = new ArrayList<>();
        SyncEquipSettings settings = parse("""
                equipment:
                  missing-item-policy: skip
                synchronization:
                  max-squad-size: 100
                """, warnings);
        assertEquals(MissingItemPolicy.SKIP, settings.missingItemPolicy());
        assertEquals(100, settings.maxSquadSize());
        assertTrue(warnings.isEmpty());
    }
}
