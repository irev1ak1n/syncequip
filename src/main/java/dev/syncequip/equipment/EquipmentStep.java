package dev.syncequip.equipment;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

/**
 * One beat of the equipment sequence: which kind of item is equipped and into which slot.
 */
public enum EquipmentStep {
    BOOTS(EquipmentSlot.FEET, "Boots", 0.1),
    LEGGINGS(EquipmentSlot.LEGS, "Leggings", 0.7),
    CHESTPLATE(EquipmentSlot.CHEST, "Chestplate", 1.2),
    HELMET(EquipmentSlot.HEAD, "Helmet", 1.8),
    SHIELD(EquipmentSlot.OFF_HAND, "Shield", 1.0);

    private final EquipmentSlot slot;
    private final String displayName;
    private final double bodyHeight;

    EquipmentStep(EquipmentSlot slot, String displayName, double bodyHeight) {
        this.slot = slot;
        this.displayName = displayName;
        this.bodyHeight = bodyHeight;
    }

    public EquipmentSlot slot() {
        return slot;
    }

    public String displayName() {
        return displayName;
    }

    /** Approximate height above the player's feet where this piece sits; used for particles. */
    public double bodyHeight() {
        return bodyHeight;
    }

    /**
     * Whether an item of this material belongs in this step. Only real armor pieces and shields
     * match; carved pumpkins, mob heads and elytra are deliberately excluded.
     */
    public boolean matches(Material material) {
        if (material == null || material.isAir()) {
            return false;
        }
        String name = material.name();
        return switch (this) {
            case BOOTS -> name.endsWith("_BOOTS");
            case LEGGINGS -> name.endsWith("_LEGGINGS");
            case CHESTPLATE -> name.endsWith("_CHESTPLATE");
            case HELMET -> name.endsWith("_HELMET");
            case SHIELD -> material == Material.SHIELD;
        };
    }

    public static Optional<EquipmentStep> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
