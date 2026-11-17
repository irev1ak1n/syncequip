package dev.syncequip.equipment;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

/** One beat of a sequence. {@link #SHIELD} is the offhand step; the name is kept for older configs. */
public enum EquipmentStep {
    BOOTS(EquipmentSlot.FEET, "Boots", "boots", 0.1),
    LEGGINGS(EquipmentSlot.LEGS, "Leggings", "leggings", 0.7),
    CHESTPLATE(EquipmentSlot.CHEST, "Chestplate", "chestplate", 1.2),
    HELMET(EquipmentSlot.HEAD, "Helmet", "helmet", 1.8),
    SHIELD(EquipmentSlot.OFF_HAND, "Shield", "offhand", 1.0),
    MAIN_HAND(EquipmentSlot.HAND, "Main hand", "mainhand", 1.0);

    public static final List<EquipmentStep> CANONICAL = List.of(BOOTS, LEGGINGS, CHESTPLATE, HELMET, SHIELD, MAIN_HAND);

    private final EquipmentSlot slot;
    private final String displayName;
    private final String configKey;
    private final double bodyHeight;

    EquipmentStep(EquipmentSlot slot, String displayName, String configKey, double bodyHeight) {
        this.slot = slot;
        this.displayName = displayName;
        this.configKey = configKey;
        this.bodyHeight = bodyHeight;
    }

    public EquipmentSlot slot() {
        return slot;
    }

    public String displayName() {
        return displayName;
    }

    public String configKey() {
        return configKey;
    }

    /** The word players use for this slot in commands. */
    public String slotWord() {
        return switch (this) {
            case BOOTS -> "feet";
            case LEGGINGS -> "legs";
            case CHESTPLATE -> "chest";
            case HELMET -> "head";
            default -> configKey;
        };
    }

    public double bodyHeight() {
        return bodyHeight;
    }

    public boolean isHand() {
        return this == MAIN_HAND || this == SHIELD;
    }

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
            case MAIN_HAND -> name.endsWith("_SWORD");
        };
    }

    /** Whether the game lets this item be worn in the step's slot (hands take anything). */
    public boolean canWear(Material material) {
        if (material == null || material.isAir() || !material.isItem()) {
            return false;
        }
        if (isHand()) {
            return true;
        }
        Equippable equippable = null;
        try {
            equippable = material.asItemType().getDefaultData(DataComponentTypes.EQUIPPABLE);
        } catch (RuntimeException e) {
            // Fall back to the names below when item data is not available.
        }
        return equippable != null ? equippable.slot() == slot : matches(material) || wornByName(material);
    }

    private boolean wornByName(Material material) {
        String name = material.name();
        return switch (this) {
            case HELMET -> material == Material.CARVED_PUMPKIN || name.endsWith("_HEAD") || name.endsWith("_SKULL");
            case CHESTPLATE -> material == Material.ELYTRA;
            default -> false;
        };
    }

    public static Optional<EquipmentStep> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (key) {
            case "boots", "feet", "foot" -> Optional.of(BOOTS);
            case "leggings", "legs" -> Optional.of(LEGGINGS);
            case "chestplate", "chest" -> Optional.of(CHESTPLATE);
            case "helmet", "head" -> Optional.of(HELMET);
            case "shield", "offhand", "off_hand" -> Optional.of(SHIELD);
            case "mainhand", "main_hand", "sword", "weapon" -> Optional.of(MAIN_HAND);
            default -> Optional.empty();
        };
    }
}
