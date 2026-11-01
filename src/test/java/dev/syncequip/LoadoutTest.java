package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.config.ConfigManager;
import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.equipment.EquipmentStep;
import dev.syncequip.equipment.Loadout;
import dev.syncequip.equipment.SlotRequirement;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Pure loadout logic: reading words, ordering, saving and loading. Needs Material data only. */
class LoadoutTest {

    @org.junit.jupiter.api.BeforeAll
    static void mock() {
        MockBukkit.mock();
    }

    @org.junit.jupiter.api.AfterAll
    static void unmock() {
        MockBukkit.unmock();
    }

    private static SlotRequirement parse(EquipmentStep step, String word) {
        SlotRequirement.Parsed parsed = SlotRequirement.parse(step, word);
        assertTrue(parsed.ok(), word + " should parse but: " + parsed.error());
        return parsed.requirement();
    }

    @Test
    void wordsForTheMainHand() {
        assertEquals(SlotRequirement.Kind.ANY_SWORD, parse(EquipmentStep.MAIN_HAND, "any_sword").kind());
        assertEquals(SlotRequirement.Kind.ANY_AXE, parse(EquipmentStep.MAIN_HAND, "ANY_AXE").kind());
        assertEquals(Material.NETHERITE_SWORD, parse(EquipmentStep.MAIN_HAND, "netherite_sword").material());
        assertEquals(Material.NETHERITE_SWORD, parse(EquipmentStep.MAIN_HAND, "netherite").material());
        assertEquals(Material.TRIDENT, parse(EquipmentStep.MAIN_HAND, "minecraft:trident").material());
        assertEquals(Material.BOW, parse(EquipmentStep.MAIN_HAND, "bow").material());
        assertEquals(Material.CROSSBOW, parse(EquipmentStep.MAIN_HAND, "crossbow").material());
        assertEquals(Material.DIAMOND_AXE, parse(EquipmentStep.MAIN_HAND, "diamond_axe").material());
        assertFalse(parse(EquipmentStep.MAIN_HAND, "none").enabled());
    }

    @Test
    void wordsForTheOffhand() {
        assertEquals(Material.SHIELD, parse(EquipmentStep.SHIELD, "shield").material());
        assertEquals(Material.TOTEM_OF_UNDYING, parse(EquipmentStep.SHIELD, "totem_of_undying").material());
        assertEquals(Material.ENDER_PEARL, parse(EquipmentStep.SHIELD, "ender_pearl").material());
        assertEquals(SlotRequirement.Kind.ANY, parse(EquipmentStep.SHIELD, "any").kind());
        assertFalse(parse(EquipmentStep.SHIELD, "none").enabled());
        assertFalse(SlotRequirement.parse(EquipmentStep.SHIELD, "any_sword").ok());
    }

    @Test
    void wordsForArmor() {
        assertEquals(Material.NETHERITE_HELMET, parse(EquipmentStep.HELMET, "netherite").material());
        assertEquals(Material.GOLDEN_BOOTS, parse(EquipmentStep.BOOTS, "gold").material());
        assertEquals(Material.IRON_CHESTPLATE, parse(EquipmentStep.CHESTPLATE, "iron_chestplate").material());
        assertEquals(Material.TURTLE_HELMET, parse(EquipmentStep.HELMET, "turtle_helmet").material());
        assertEquals(SlotRequirement.Kind.ANY, parse(EquipmentStep.LEGGINGS, "any").kind());
        assertFalse(parse(EquipmentStep.LEGGINGS, "none").enabled());
    }

    @Test
    void wrongOrUnknownWordsAreRejectedWithAReason() {
        SlotRequirement.Parsed wrongSlot = SlotRequirement.parse(EquipmentStep.HELMET, "netherite_boots");
        assertFalse(wrongSlot.ok());
        assertTrue(wrongSlot.error().contains("does not fit"));
        assertFalse(SlotRequirement.parse(EquipmentStep.MAIN_HAND, "banana").ok());
        assertFalse(SlotRequirement.parse(EquipmentStep.MAIN_HAND, "").ok());
        assertFalse(SlotRequirement.parse(EquipmentStep.BOOTS, "any_sword").ok());
        assertFalse(SlotRequirement.parse(EquipmentStep.MAIN_HAND, "air").ok());
    }

    @Test
    void matchingRules() {
        SlotRequirement anySword = parse(EquipmentStep.MAIN_HAND, "any_sword");
        assertTrue(anySword.matches(EquipmentStep.MAIN_HAND, Material.WOODEN_SWORD));
        assertTrue(anySword.matches(EquipmentStep.MAIN_HAND, Material.NETHERITE_SWORD));
        assertFalse(anySword.matches(EquipmentStep.MAIN_HAND, Material.DIAMOND_AXE));
        SlotRequirement axe = parse(EquipmentStep.MAIN_HAND, "any_axe");
        assertTrue(axe.matches(EquipmentStep.MAIN_HAND, Material.IRON_AXE));
        assertFalse(axe.matches(EquipmentStep.MAIN_HAND, Material.IRON_PICKAXE), "a pickaxe is not an axe");
        SlotRequirement helmet = parse(EquipmentStep.HELMET, "any");
        assertTrue(helmet.matches(EquipmentStep.HELMET, Material.LEATHER_HELMET));
        assertFalse(helmet.matches(EquipmentStep.HELMET, Material.CARVED_PUMPKIN));
        assertFalse(SlotRequirement.NONE.matches(EquipmentStep.HELMET, Material.IRON_HELMET));
    }

    @Test
    void theStandardLoadoutIsSwordShieldAndFourArmorPiecesWithTheSwordLast() {
        Loadout standard = Loadout.standard();
        assertEquals(List.of(EquipmentStep.BOOTS, EquipmentStep.LEGGINGS, EquipmentStep.CHESTPLATE, EquipmentStep.HELMET,
                EquipmentStep.SHIELD, EquipmentStep.MAIN_HAND), standard.steps());
        assertEquals(List.of(EquipmentStep.MAIN_HAND, EquipmentStep.SHIELD, EquipmentStep.HELMET,
                EquipmentStep.CHESTPLATE, EquipmentStep.LEGGINGS, EquipmentStep.BOOTS), standard.unequipSteps());
        assertEquals("Shield", standard.label(EquipmentStep.SHIELD));
        assertEquals("Sword", standard.label(EquipmentStep.MAIN_HAND));
        assertEquals("Helmet", standard.label(EquipmentStep.HELMET));
    }

    @Test
    void disabledSlotsLeaveTheSequence() {
        Loadout loadout = Loadout.standard()
                .with(EquipmentStep.HELMET, SlotRequirement.NONE)
                .with(EquipmentStep.MAIN_HAND, SlotRequirement.NONE);
        assertEquals(List.of(EquipmentStep.BOOTS, EquipmentStep.LEGGINGS, EquipmentStep.CHESTPLATE, EquipmentStep.SHIELD),
                loadout.steps());
    }

    @Test
    void customOrderPutsListedStepsFirstAndKeepsTheRest() {
        Loadout loadout = Loadout.standard().withOrder(List.of(EquipmentStep.MAIN_HAND, EquipmentStep.HELMET));
        assertEquals(List.of(EquipmentStep.MAIN_HAND, EquipmentStep.HELMET, EquipmentStep.BOOTS, EquipmentStep.LEGGINGS,
                EquipmentStep.CHESTPLATE, EquipmentStep.SHIELD), loadout.steps());
    }

    @Test
    void legacyLoadoutUsesOnlyTheListedStepsInTheirOrder() {
        Loadout legacy = Loadout.legacy(List.of(EquipmentStep.SHIELD, EquipmentStep.HELMET, EquipmentStep.BOOTS));
        assertEquals(List.of(EquipmentStep.SHIELD, EquipmentStep.HELMET, EquipmentStep.BOOTS), legacy.steps());
        assertFalse(legacy.requirement(EquipmentStep.MAIN_HAND).enabled());
        assertEquals(Material.SHIELD, legacy.requirement(EquipmentStep.SHIELD).material());
    }

    @Test
    void saveAndLoadRoundTrip() {
        Loadout original = Loadout.standard()
                .with(EquipmentStep.HELMET, SlotRequirement.of(Material.NETHERITE_HELMET))
                .with(EquipmentStep.SHIELD, SlotRequirement.of(Material.TOTEM_OF_UNDYING))
                .with(EquipmentStep.BOOTS, SlotRequirement.NONE)
                .with(EquipmentStep.MAIN_HAND, parse(EquipmentStep.MAIN_HAND, "any_axe").withOptional(true))
                .withOrder(List.of(EquipmentStep.MAIN_HAND, EquipmentStep.SHIELD));
        YamlConfiguration yaml = new YamlConfiguration();
        original.save(yaml.createSection("loadout"));

        Loadout loaded = Loadout.load(yaml.getConfigurationSection("loadout"), Loadout.standard(), "test", new ArrayList<>());

        assertEquals(original, loaded);
        assertEquals(original.hashCode(), loaded.hashCode());
    }

    @Test
    void badWordsInAConfigKeepTheOldValueAndWarn() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("default.helmet", "banana");
        yaml.set("default.mainhand", "diamond_axe");
        yaml.set("default.order", List.of("boots", "tiara"));
        List<String> warnings = new ArrayList<>();

        Loadout loadout = Loadout.load(yaml.getConfigurationSection("default"), Loadout.standard(), "loadout.default", warnings);

        assertEquals(SlotRequirement.Kind.ANY, loadout.requirement(EquipmentStep.HELMET).kind());
        assertEquals(Material.DIAMOND_AXE, loadout.requirement(EquipmentStep.MAIN_HAND).material());
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void anOldConfigWithoutALoadoutSectionGetsTheStandardLoadout() {
        SyncEquipSettings settings = ConfigManager.defaults();
        assertEquals(Loadout.standard().withOrder(ConfigManager.DEFAULT_ORDER), settings.defaultLoadout());
        assertEquals(2, settings.intervalTicks());
        assertFalse(settings.soloUseDefaultLoadout());
        assertEquals(Loadout.legacy(ConfigManager.DEFAULT_ORDER), settings.soloLoadout(), "solo keeps the original behaviour");
        assertTrue(settings.pads().enabled());
    }

    @Test
    void theBundledConfigParsesWithoutWarningsAndMatchesTheDefaults() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                java.util.Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                java.nio.charset.StandardCharsets.UTF_8));
        List<String> warnings = new ArrayList<>();
        SyncEquipSettings settings = ConfigManager.parse(config, warnings);
        assertEquals(List.of(), warnings);
        assertEquals(ConfigManager.defaults().defaultLoadout(), settings.defaultLoadout());
        assertEquals(3, settings.unequipCountdownSeconds());
        assertNotEquals(null, settings.sounds().mainHandSound());
    }

    @Test
    void theOrderSettingAcceptsMainHandAndOffhandNames() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("equipment.order", List.of("MAIN_HAND", "OFFHAND", "boots"));
        SyncEquipSettings settings = ConfigManager.parse(config, new ArrayList<>());
        assertEquals(List.of(EquipmentStep.MAIN_HAND, EquipmentStep.SHIELD, EquipmentStep.BOOTS), settings.order());
        assertEquals(EquipmentStep.MAIN_HAND, settings.defaultLoadout().steps().getFirst());
    }
}
