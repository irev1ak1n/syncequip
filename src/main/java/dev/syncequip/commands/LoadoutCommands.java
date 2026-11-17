package dev.syncequip.commands;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.SyncEquipPlugin;
import dev.syncequip.equipment.EquipmentStep;
import dev.syncequip.equipment.Loadout;
import dev.syncequip.equipment.SlotRequirement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** The personal solo loadout commands, plus the slot and item handling shared with squad loadouts. */
public final class LoadoutCommands {
    public static final List<String> SLOTS = List.of("mainhand", "offhand", "head", "chest", "legs", "feet");
    public static final List<String> LEGACY_SLOTS = List.of("mainhand", "offhand", "helmet", "chestplate", "leggings",
            "boots");
    private static final List<String> SOLO_ACTIONS = List.of("set", "show", "reset");

    /** A validated change to one slot. */
    public record Change(EquipmentStep step, SlotRequirement requirement, Loadout loadout) {
    }

    private final SyncEquipPlugin plugin;

    public LoadoutCommands(SyncEquipPlugin plugin) {
        this.plugin = plugin;
    }

    public void solo(Player player, String[] args) {
        String action = args[1].toLowerCase(Locale.ROOT);
        Loadout current = plugin.sequenceService().soloLoadoutOf(player, plugin.settings());
        switch (action) {
            case "show" -> show(player, "loadout.header-solo", player.getName(),
                    plugin.soloLoadouts().get(player.getUniqueId()).isPresent() ? "your own" : "server default", current);
            case "reset" -> {
                if (!soloIdle(player)) {
                    return;
                }
                plugin.soloLoadouts().reset(player.getUniqueId());
                plugin.messages().send(player, "loadout.solo-reset");
            }
            case "set" -> {
                Change change = parseSet(player, current, rest(args, 2));
                if (change == null || !soloIdle(player)) {
                    return;
                }
                plugin.soloLoadouts().set(player.getUniqueId(), change.loadout());
                announce(player, change);
            }
            default -> plugin.messages().send(player, "loadout.usage-solo");
        }
    }

    private boolean soloIdle(Player player) {
        if (plugin.sequenceService().scheduler().isBusy(player.getUniqueId())) {
            plugin.messages().send(player, "loadout.solo-busy");
            return false;
        }
        return true;
    }

    /** Parses {@code <slot> <item|any|off> [optional]}; tells the player what is wrong and returns null if needed. */
    Change parseSet(Player player, Loadout current, String[] words) {
        if (words.length < 2) {
            plugin.messages().send(player, "loadout.usage-set");
            return null;
        }
        Optional<EquipmentStep> step = EquipmentStep.parse(words[0]);
        if (step.isEmpty()) {
            plugin.messages().send(player, "loadout.unknown-slot", ph("slot", words[0]));
            return null;
        }
        SlotRequirement.Parsed parsed = SlotRequirement.parse(step.get(), words[1]);
        if (!parsed.ok()) {
            plugin.messages().send(player, "loadout.invalid", ph("reason", parsed.error()));
            return null;
        }
        boolean optional = words.length > 2 ? words[2].equalsIgnoreCase("optional")
                : current.requirement(step.get()).optional();
        SlotRequirement requirement = parsed.requirement().withOptional(optional);
        return new Change(step.get(), requirement, current.with(step.get(), requirement));
    }

    void announce(Player player, Change change) {
        plugin.messages().send(player, "loadout.set", ph("slot", label(change.step())),
                ph("value", change.requirement().token()));
    }

    void show(Player player, String headerKey, String name, String source, Loadout loadout) {
        plugin.messages().sendPlain(player, headerKey, ph("squad", name), ph("source", source));
        for (EquipmentStep step : loadout.order()) {
            SlotRequirement req = loadout.requirement(step);
            plugin.messages().sendPlain(player, "loadout.line", ph("slot", label(step)),
                    ph("value", req.token()), ph("flags", req.optional() ? " (optional)" : ""));
        }
        plugin.messages().sendPlain(player, "loadout.order", ph("steps", describe(loadout)));
    }

    static String describe(Loadout loadout) {
        List<String> parts = new ArrayList<>();
        for (EquipmentStep step : loadout.steps()) {
            parts.add(loadout.label(step) + (loadout.requirement(step).optional() ? "?" : ""));
        }
        return parts.isEmpty() ? "nothing" : String.join(" > ", parts);
    }

    public List<String> completeSolo(String[] args) {
        if (args.length == 2) {
            return filter(SOLO_ACTIONS.stream(), args[1]);
        }
        if (!args[1].equalsIgnoreCase("set")) {
            return List.of();
        }
        return completeSet(args, 2, SLOTS);
    }

    /** Completes {@code <slot> <item> [optional]} where the slot word sits at {@code args[from]}. */
    List<String> completeSet(String[] args, int from, List<String> slots) {
        int position = args.length - 1 - from;
        return switch (position) {
            case 0 -> filter(slots.stream(), args[from]);
            case 1 -> filter(valuesFor(args[from], args[from + 1]), args[from + 1]);
            case 2 -> filter(Stream.of("optional"), args[from + 2]);
            default -> List.of();
        };
    }

    static Stream<String> valuesFor(String slotWord, String typed) {
        Optional<EquipmentStep> step = EquipmentStep.parse(slotWord);
        List<String> values = new ArrayList<>(List.of("off", "none", "any"));
        if (step.isEmpty()) {
            return values.stream();
        }
        switch (step.get()) {
            case MAIN_HAND -> values.addAll(List.of("any_sword", "any_axe", "netherite_sword", "diamond_sword",
                    "iron_sword", "netherite_axe", "trident", "mace", "bow", "crossbow"));
            case SHIELD -> values.addAll(List.of("shield", "totem_of_undying", "ender_pearl"));
            case HELMET -> values.addAll(List.of("netherite", "diamond", "iron", "carved_pumpkin", "player_head",
                    "turtle_helmet"));
            case CHESTPLATE -> values.addAll(List.of("netherite", "diamond", "iron", "elytra"));
            default -> values.addAll(List.of("netherite", "diamond", "iron", "gold", "chainmail", "leather"));
        }
        if (!typed.isEmpty()) {
            String lower = typed.toLowerCase(Locale.ROOT);
            Stream.of(Material.values())
                    .filter(m -> !m.isLegacy() && m.isItem() && !m.isAir())
                    .filter(m -> m.name().toLowerCase(Locale.ROOT).startsWith(lower) && step.get().canWear(m))
                    .map(m -> m.name().toLowerCase(Locale.ROOT))
                    .limit(40)
                    .forEach(values::add);
        }
        return values.stream().distinct();
    }

    static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).forEach(result::add);
        return result;
    }

    static String[] rest(String[] args, int from) {
        return from >= args.length ? new String[0] : java.util.Arrays.copyOfRange(args, from, args.length);
    }

    private static String label(EquipmentStep step) {
        return step == EquipmentStep.SHIELD ? "Offhand" : step.displayName();
    }
}
