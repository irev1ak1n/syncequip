package dev.syncequip.config;

import dev.syncequip.equipment.EquipmentStep;
import dev.syncequip.equipment.Loadout;
import dev.syncequip.equipment.MissingItemPolicy;
import java.util.List;

public record SyncEquipSettings(
        int intervalTicks,
        List<EquipmentStep> order,
        boolean requireEmptySlots,
        MissingItemPolicy missingItemPolicy,
        boolean lockInventoryDuringSequence,
        int soloCountdownSeconds,
        int squadCountdownSeconds,
        int maxSquadSize,
        int inviteExpirySeconds,
        Sounds sounds,
        Visuals visuals,
        Controls controls,
        Demo demo,
        Loadout defaultLoadout,
        boolean soloUseDefaultLoadout,
        int unequipCountdownSeconds,
        Blocks blocks,
        boolean debugLogTicks) {

    public SyncEquipSettings {
        order = List.copyOf(order);
    }

    /** Loadout used by solo sequences: the original armor and shield behaviour unless the config says otherwise. */
    public Loadout soloLoadout() {
        return soloUseDefaultLoadout ? defaultLoadout : Loadout.legacy(order);
    }

    public record Sounds(
            boolean enabled,
            boolean useVanillaArmorSounds,
            float volume,
            float pitch,
            String armorFallbackSound,
            String shieldSound,
            float shieldVolume,
            float shieldPitch,
            boolean countdownSoundEnabled,
            String countdownSound,
            boolean goSoundEnabled,
            String goSound,
            boolean completionSoundEnabled,
            String completionSound,
            String mainHandSound,
            float mainHandVolume,
            float mainHandPitch,
            float unequipPitchMultiplier) {
    }

    /** Joining blocks. {@code allWorlds}: a material applies in every world instead of only the world it was set in. */
    public record Blocks(boolean enabled, int noticeCooldownSeconds, boolean allWorlds) {
    }

    public enum CountdownDisplay {
        TITLE,
        ACTIONBAR,
        NONE
    }

    public record Visuals(boolean particlesEnabled, boolean actionbarEnabled, CountdownDisplay countdownDisplay) {
    }

    public enum HotkeyAction {
        SOLO,
        AUTO
    }

    public enum DemoFacing {
        TOWARD_PLAYER,
        SAME_AS_PLAYER
    }

    public record Demo(int intervalTicks, int countdownSeconds, int repeatHoldSeconds, int maxMobs, float spacing,
                       float distance, DemoFacing facing) {
    }

    public record Controls(boolean sneakSwapTrigger, HotkeyAction sneakSwapAction, int cooldownSeconds) {
    }
}
