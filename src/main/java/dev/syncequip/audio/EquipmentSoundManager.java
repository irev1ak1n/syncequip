package dev.syncequip.audio;

import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.equipment.EquipmentStep;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/** With custom sounds on, armor is equipped silently and this class plays one sound per step. With them off, it plays nothing. */
public final class EquipmentSoundManager {

    public static String vanillaArmorSound(Material material, String fallback) {
        String name = material.name();
        if (material == Material.TURTLE_HELMET) {
            return "minecraft:item.armor.equip_turtle";
        }
        if (name.startsWith("LEATHER_")) {
            return "minecraft:item.armor.equip_leather";
        }
        if (name.startsWith("CHAINMAIL_")) {
            return "minecraft:item.armor.equip_chain";
        }
        if (name.startsWith("IRON_")) {
            return "minecraft:item.armor.equip_iron";
        }
        if (name.startsWith("GOLDEN_")) {
            return "minecraft:item.armor.equip_gold";
        }
        if (name.startsWith("DIAMOND_")) {
            return "minecraft:item.armor.equip_diamond";
        }
        if (name.startsWith("NETHERITE_")) {
            return "minecraft:item.armor.equip_netherite";
        }
        if (name.startsWith("COPPER_")) {
            return "minecraft:item.armor.equip_copper";
        }
        return fallback;
    }

    public static String soundFor(EquipmentStep step, Material material, SyncEquipSettings.Sounds sounds) {
        if (!sounds.enabled()) {
            return null;
        }
        if (step == EquipmentStep.SHIELD) {
            return sounds.shieldSound();
        }
        return sounds.useVanillaArmorSounds()
                ? vanillaArmorSound(material, sounds.armorFallbackSound())
                : sounds.armorFallbackSound();
    }

    public void playStep(Player player, EquipmentStep step, Material material, SyncEquipSettings.Sounds sounds) {
        String key = soundFor(step, material, sounds);
        if (key == null) {
            return;
        }
        boolean shield = step == EquipmentStep.SHIELD;
        float volume = shield ? sounds.shieldVolume() : sounds.volume();
        float pitch = shield ? sounds.shieldPitch() : sounds.pitch();
        Location location = player.getLocation();
        player.getWorld().playSound(location, key, SoundCategory.PLAYERS, volume, pitch);
    }

    public void playCue(Player player, String key, float volume, float pitch) {
        player.playSound(player.getLocation(), key, SoundCategory.PLAYERS, volume, pitch);
    }
}
