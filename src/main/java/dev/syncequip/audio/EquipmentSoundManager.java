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
        if (step == EquipmentStep.MAIN_HAND) {
            return sounds.mainHandSound();
        }
        return sounds.useVanillaArmorSounds()
                ? vanillaArmorSound(material, sounds.armorFallbackSound())
                : sounds.armorFallbackSound();
    }

    public void playStep(Player player, EquipmentStep step, Material material, SyncEquipSettings.Sounds sounds) {
        playStep(player.getLocation(), step, material, sounds, 1.0f);
    }

    public void playStep(Location location, EquipmentStep step, Material material, SyncEquipSettings.Sounds sounds) {
        playStep(location, step, material, sounds, 1.0f);
    }

    /** Plays the step sound at a position for everyone in hearing range. Taking things off passes a lower pitch. */
    public void playStep(Location location, EquipmentStep step, Material material, SyncEquipSettings.Sounds sounds,
                         float pitchMultiplier) {
        String key = soundFor(step, material, sounds);
        if (key == null) {
            return;
        }
        float volume = switch (step) {
            case SHIELD -> sounds.shieldVolume();
            case MAIN_HAND -> sounds.mainHandVolume();
            default -> sounds.volume();
        };
        float pitch = switch (step) {
            case SHIELD -> sounds.shieldPitch();
            case MAIN_HAND -> sounds.mainHandPitch();
            default -> sounds.pitch();
        };
        float finalPitch = Math.max(0.5f, Math.min(2.0f, pitch * pitchMultiplier));
        location.getWorld().playSound(location, key, SoundCategory.PLAYERS, volume, finalPitch);
    }

    public void playCue(Player player, String key, float volume, float pitch) {
        player.playSound(player.getLocation(), key, SoundCategory.PLAYERS, volume, pitch);
    }
}
