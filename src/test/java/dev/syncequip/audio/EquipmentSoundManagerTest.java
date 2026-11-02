package dev.syncequip.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.syncequip.config.ConfigManager;
import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.equipment.EquipmentStep;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class EquipmentSoundManagerTest {
    private static final String FALLBACK = "minecraft:item.armor.equip_generic";

    @Test
    void eachArmorMaterialHasItsVanillaSound() {
        assertEquals("minecraft:item.armor.equip_leather", EquipmentSoundManager.vanillaArmorSound(Material.LEATHER_BOOTS, FALLBACK));
        assertEquals("minecraft:item.armor.equip_chain", EquipmentSoundManager.vanillaArmorSound(Material.CHAINMAIL_LEGGINGS, FALLBACK));
        assertEquals("minecraft:item.armor.equip_iron", EquipmentSoundManager.vanillaArmorSound(Material.IRON_CHESTPLATE, FALLBACK));
        assertEquals("minecraft:item.armor.equip_gold", EquipmentSoundManager.vanillaArmorSound(Material.GOLDEN_HELMET, FALLBACK));
        assertEquals("minecraft:item.armor.equip_diamond", EquipmentSoundManager.vanillaArmorSound(Material.DIAMOND_BOOTS, FALLBACK));
        assertEquals("minecraft:item.armor.equip_netherite", EquipmentSoundManager.vanillaArmorSound(Material.NETHERITE_LEGGINGS, FALLBACK));
        assertEquals("minecraft:item.armor.equip_turtle", EquipmentSoundManager.vanillaArmorSound(Material.TURTLE_HELMET, FALLBACK));
    }

    @Test
    void shieldUsesTheConfiguredShieldSound() {
        SyncEquipSettings.Sounds sounds = ConfigManager.defaults().sounds();
        assertEquals("minecraft:item.shield.block", EquipmentSoundManager.soundFor(EquipmentStep.SHIELD, Material.SHIELD, sounds));
    }

    @Test
    void disabledCustomSoundsPlayNothingSoVanillaIsNotDoubled() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("sounds.enabled", false);
        SyncEquipSettings.Sounds sounds = ConfigManager.parse(config, new java.util.ArrayList<>()).sounds();
        for (EquipmentStep step : EquipmentStep.values()) {
            assertNull(EquipmentSoundManager.soundFor(step, Material.IRON_BOOTS, sounds));
        }
    }

    @Test
    void genericSoundWhenVanillaMaterialSoundsAreOff() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("sounds.use-vanilla-armor-sounds", false);
        SyncEquipSettings.Sounds sounds = ConfigManager.parse(config, new java.util.ArrayList<>()).sounds();
        for (Material material : List.of(Material.IRON_BOOTS, Material.DIAMOND_HELMET)) {
            assertEquals(FALLBACK, EquipmentSoundManager.soundFor(EquipmentStep.BOOTS, material, sounds));
        }
    }
}
