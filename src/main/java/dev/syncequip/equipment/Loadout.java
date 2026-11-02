package dev.syncequip.equipment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

public final class Loadout {
    private final Map<EquipmentStep, SlotRequirement> requirements;
    private final List<EquipmentStep> order;

    private Loadout(Map<EquipmentStep, SlotRequirement> requirements, List<EquipmentStep> order) {
        this.requirements = requirements;
        this.order = List.copyOf(order);
    }

    public static Loadout standard() {
        Map<EquipmentStep, SlotRequirement> map = new EnumMap<>(EquipmentStep.class);
        for (EquipmentStep step : EquipmentStep.CANONICAL) {
            map.put(step, SlotRequirement.ANY);
        }
        map.put(EquipmentStep.SHIELD, SlotRequirement.of(Material.SHIELD));
        map.put(EquipmentStep.MAIN_HAND, new SlotRequirement(SlotRequirement.Kind.ANY_SWORD, null, false));
        return new Loadout(map, EquipmentStep.CANONICAL);
    }

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

    public List<EquipmentStep> steps() {
        return order.stream().filter(step -> requirements.get(step).enabled()).toList();
    }

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
