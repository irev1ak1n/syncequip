package dev.syncequip.equipment;

import java.util.Locale;
import org.bukkit.Material;

public record SlotRequirement(Kind kind, Material material, boolean optional) {
    public enum Kind {
        NONE,
        ANY,
        ANY_SWORD,
        ANY_AXE,
        MATERIAL
    }

    public record Parsed(SlotRequirement requirement, String error) {
        public boolean ok() {
            return requirement != null;
        }
    }

    public static final SlotRequirement NONE = new SlotRequirement(Kind.NONE, null, false);
    public static final SlotRequirement ANY = new SlotRequirement(Kind.ANY, null, false);

    public static SlotRequirement of(Material material) {
        return new SlotRequirement(Kind.MATERIAL, material, false);
    }

    public boolean enabled() {
        return kind != Kind.NONE;
    }

    public SlotRequirement withOptional(boolean optional) {
        return new SlotRequirement(kind, material, optional);
    }

    public boolean matches(EquipmentStep step, Material type) {
        if (type == null || type.isAir()) {
            return false;
        }
        return switch (kind) {
            case NONE -> false;
            case ANY -> step.matches(type);
            case ANY_SWORD -> type.name().endsWith("_SWORD");
            case ANY_AXE -> type.name().endsWith("_AXE");
            case MATERIAL -> type == material;
        };
    }

    public String token() {
        return switch (kind) {
            case NONE -> "none";
            case ANY -> "any";
            case ANY_SWORD -> "any_sword";
            case ANY_AXE -> "any_axe";
            case MATERIAL -> material.name().toLowerCase(Locale.ROOT);
        };
    }

    public String label(EquipmentStep step) {
        return switch (kind) {
            case NONE -> step.displayName();
            case ANY -> step == EquipmentStep.MAIN_HAND ? "Sword" : step.displayName();
            case ANY_SWORD -> "Sword";
            case ANY_AXE -> "Axe";
            case MATERIAL -> pretty(material);
        };
    }

    public static String pretty(Material material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.name().toLowerCase(Locale.ROOT).split("_")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    public static Parsed parse(EquipmentStep step, String raw) {
        if (raw == null || raw.isBlank()) {
            return fail("Give an item name, any or none.");
        }
        String word = raw.trim().toLowerCase(Locale.ROOT);
        if (word.startsWith("minecraft:")) {
            word = word.substring("minecraft:".length());
        }
        switch (word) {
            case "none", "off", "disabled", "disable" -> {
                return ok(NONE);
            }
            case "any" -> {
                return ok(ANY);
            }
            case "any_sword" -> {
                return step == EquipmentStep.MAIN_HAND ? ok(new SlotRequirement(Kind.ANY_SWORD, null, false))
                        : fail("any_sword only works for the main hand.");
            }
            case "any_axe" -> {
                return step == EquipmentStep.MAIN_HAND ? ok(new SlotRequirement(Kind.ANY_AXE, null, false))
                        : fail("any_axe only works for the main hand.");
            }
            default -> {
            }
        }
        Material material = lookup(word);
        if (material == null && step == EquipmentStep.MAIN_HAND) {
            material = lookup(word + "_sword");
        }
        if (material == null && !step.isHand()) {
            String family = word.equals("gold") ? "golden" : word;
            material = lookup(family + "_" + step.name().toLowerCase(Locale.ROOT));
        }
        if (material == null) {
            return fail("'" + raw.trim() + "' is not an item I know.");
        }
        if (!step.isHand() && !step.matches(material)) {
            return fail(pretty(material) + " does not fit the " + step.displayName().toLowerCase(Locale.ROOT) + " slot.");
        }
        return ok(of(material));
    }

    private static Material lookup(String name) {
        Material material = Material.matchMaterial(name);
        return material != null && !material.isLegacy() && material.isItem() && !material.isAir() ? material : null;
    }

    private static Parsed ok(SlotRequirement requirement) {
        return new Parsed(requirement, null);
    }

    private static Parsed fail(String message) {
        return new Parsed(null, message);
    }
}
