package dev.syncequip.equipment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Which real inventory items a player must have for each slot, and the order the slots are filled.
 * Immutable. Slots set to {@code none} take no part in validation, sequencing or sounds.
 */
public final class Loadout {

    private final Map<EquipmentStep, SlotRequirement> requirements;
    private final List<EquipmentStep> order;

    private Loadout(Map<EquipmentStep, SlotRequirement> requirements, List<EquipmentStep> order) {
        this.requirements = requirements;
        this.order = List.copyOf(order);
    }

    /** Sword, shield and the four armor pieces, with the main hand last. */
    public static Loadout standard() {
        Map<EquipmentStep, SlotRequirement> map = new EnumMap<>(EquipmentStep.class);
        for (EquipmentStep step : EquipmentStep.CANONICAL) {
            map.put(step, SlotRequirement.ANY);
        }
        map.put(EquipmentStep.SHIELD, SlotRequirement.of(Material.SHIELD));
        map.put(EquipmentStep.MAIN_HAND, new SlotRequirement(SlotRequirement.Kind.ANY_SWORD, null, false));
        return new Loadout(map, EquipmentStep.CANONICAL);
    }

    /**
     * The original behaviour: only the listed steps take part, armor and shield only, in the given order.
     * Used for solo sequences and for older configs.
     */
    public static Loadout legacy(List<EquipmentStep> steps) {
        Map<EquipmentStep, SlotRequirement> map = new EnumMap<>(EquipmentStep.class);
        for (EquipmentStep step : EquipmentStep.CANONICAL) {
            map.put(step, SlotRequirement.NONE);
        }
        for (EquipmentStep step : steps) {
            map.put(step, switch (step) {
                case SHIELD -> SlotRequirement.of(Material.SHIELD);
                case MAIN_HAND -> new SlotRequirement(SlotRequirement.Kind.ANY_SWORD, null, false);
                default -> SlotRequirement.ANY;
            });
        }
        return new Loadout(map, steps);
    }

    public SlotRequirement requirement(EquipmentStep step) {
        return requirements.get(step);
    }

    public Loadout with(EquipmentStep step, SlotRequirement requirement) {
        Map<EquipmentStep, SlotRequirement> map = new EnumMap<>(requirements);
        map.put(step, requirement);
        return new Loadout(map, order);
    }

    /** Puts the listed steps first, in the given order, and every other step after them. */
    public Loadout withOrder(List<EquipmentStep> preferred) {
        List<EquipmentStep> merged = new ArrayList<>();
        for (EquipmentStep step : preferred) {
            if (!merged.contains(step)) {
                merged.add(step);
            }
        }
        for (EquipmentStep step : EquipmentStep.CANONICAL) {
            if (!merged.contains(step)) {
                merged.add(step);
            }
        }
        return new Loadout(requirements, merged);
    }

    public List<EquipmentStep> order() {
        return order;
    }

    /** Steps that take part, in sequence order. */
    public List<EquipmentStep> steps() {
        return order.stream().filter(step -> requirements.get(step).enabled()).toList();
    }

    /** The same steps in reverse, for taking equipment off. */
    public List<EquipmentStep> unequipSteps() {
        List<EquipmentStep> steps = new ArrayList<>(steps());
        java.util.Collections.reverse(steps);
        return steps;
    }

    public String label(EquipmentStep step) {
        return requirements.get(step).label(step);
    }

    public String describe(List<EquipmentStep> steps) {
        return String.join(", ", steps.stream().map(this::label).toList());
    }

    /** Writes this loadout into a config section using the same words the commands use. */
    public void save(ConfigurationSection section) {
        List<String> optional = new ArrayList<>();
        for (EquipmentStep step : EquipmentStep.CANONICAL) {
            SlotRequirement req = requirements.get(step);
            section.set(step.configKey(), req.token());
            if (req.optional()) {
                optional.add(step.configKey());
            }
        }
        section.set("optional", optional);
        section.set("order", order.stream().map(step -> step.name().toLowerCase(java.util.Locale.ROOT)).toList());
    }

    /**
     * Reads a loadout section on top of {@code base}. Words that cannot be read are ignored and reported in
     * {@code warnings}, so a typo never leaves a player without a working loadout.
     */
    public static Loadout load(ConfigurationSection section, Loadout base, String path, List<String> warnings) {
        if (section == null) {
            return base;
        }
        Loadout result = base;
        for (EquipmentStep step : EquipmentStep.CANONICAL) {
            if (!section.isSet(step.configKey())) {
                continue;
            }
            SlotRequirement.Parsed parsed = SlotRequirement.parse(step, String.valueOf(section.get(step.configKey())));
            if (parsed.ok()) {
                result = result.with(step, parsed.requirement().withOptional(result.requirement(step).optional()));
            } else {
                warnings.add(path + "." + step.configKey() + ": " + parsed.error() + " Keeping "
                        + result.requirement(step).token() + ".");
            }
        }
        if (section.isList("optional")) {
            List<String> names = section.getStringList("optional");
            for (EquipmentStep step : EquipmentStep.CANONICAL) {
                boolean on = names.stream().anyMatch(n -> EquipmentStep.parse(n).filter(step::equals).isPresent());
                result = result.with(step, result.requirement(step).withOptional(on));
            }
        }
        if (section.isList("order")) {
            List<EquipmentStep> preferred = new ArrayList<>();
            for (String name : section.getStringList("order")) {
                EquipmentStep.parse(name).ifPresentOrElse(preferred::add,
                        () -> warnings.add(path + ".order: unknown step '" + name + "' ignored"));
            }
            if (!preferred.isEmpty()) {
                result = result.withOrder(preferred);
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Loadout that && requirements.equals(that.requirements) && order.equals(that.order);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requirements, order);
    }
}
