package dev.syncequip.audio;

import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.equipment.EquipmentStep;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * Plays the per-step equip sounds.
 *
 * <p>When custom sounds are enabled, armor is equipped silently (see
 * {@link dev.syncequip.equipment.EquipmentManager#equip}) and this class plays exactly one sound per
 * step at the player's position, so nearby players hear it spatially. When custom sounds are
 * disabled, vanilla plays its own armor sounds and this class stays quiet, so a step never produces
 * two sounds.
 */
public final class EquipmentSoundManager {

    /** Vanilla equip sound for an armor material, chosen by the material's name prefix. */
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

    /** The sound key SyncEquip plays for a step, or {@code null} when custom sounds are disabled. */
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

    /** Plays the step sound at the player's location for everyone in hearing range. */
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

    /** Plays a cue (countdown tick, "go", completion) only to the given player. */
    public void playCue(Player player, String key, float volume, float pitch) {
        player.playSound(player.getLocation(), key, SoundCategory.PLAYERS, volume, pitch);
    }
}
