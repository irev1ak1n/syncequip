package dev.syncequip.config;

import dev.syncequip.equipment.EquipmentStep;
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
        boolean debugLogTicks) {

    public SyncEquipSettings {
        order = List.copyOf(order);
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
            String completionSound) {
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

    public record Controls(boolean sneakSwapTrigger, HotkeyAction sneakSwapAction, int cooldownSeconds) {
    }
}
