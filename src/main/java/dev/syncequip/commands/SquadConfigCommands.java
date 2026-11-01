package dev.syncequip.commands;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.SyncEquipPlugin;
import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.equipment.EquipmentStep;
import dev.syncequip.equipment.Loadout;
import dev.syncequip.equipment.SlotRequirement;
import dev.syncequip.squad.Pad;
import dev.syncequip.squad.PadService;
import dev.syncequip.squad.Squad;
import dev.syncequip.squad.SquadManager;
import dev.syncequip.squad.SquadProfile;
import dev.syncequip.squad.SquadSequenceController;
import dev.syncequip.synchronization.FinishReason;
import dev.syncequip.synchronization.SequenceTimeline;
import dev.syncequip.util.MessageUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The squad subcommands that configure a squad: {@code info}, {@code speed}, {@code pad}, {@code loadout} and
 * {@code disband}. Leaders and administrators change things, members can look.
 */
public final class SquadConfigCommands {

    private static final List<String> SLOTS = List.of("mainhand", "offhand", "helmet", "chestplate", "leggings", "boots");
    private static final List<String> PAD_ACTIONS = List.of("set", "remove", "info", "enable", "disable");
    private static final List<String> LOADOUT_ACTIONS = List.of("show", "reset", "set", "optional", "order");

    /** The squad a command is about: always has a profile, and a live squad if it is currently running. */
    private record Target(SquadProfile profile, Squad live) {
    }

    private final SyncEquipPlugin plugin;

    public SquadConfigCommands(SyncEquipPlugin plugin) {
        this.plugin = plugin;
    }

    public void handle(Player player, String sub, String[] args) {
        switch (sub) {
            case "info" -> info(player, args.length > 2 ? args[2] : null);
            case "speed" -> speed(player, args);
            case "pad" -> pad(player, args);
            case "loadout" -> loadout(player, args);
            case "disband" -> disband(player, args.length > 2 ? args[2] : null);
            default -> plugin.messages().send(player, "command.squad-usage");
        }
    }

    private MessageUtil messages() {
        return plugin.messages();
    }

    private SquadManager squads() {
        return plugin.squads();
    }

    // ------------------------------------------------------------------ finding the squad a command is about

    /**
     * Works out which squad to act on, and checks the player is allowed to.
     *
     * @param nameArg   an optional squad name, which only administrators may use for squads other than their own
     * @param configure true if the action changes something, false if looking is enough
     * @return the target, or {@code null} after telling the player what is wrong
     */
    private Target resolve(Player player, String nameArg, boolean configure) {
        Optional<Squad> own = squads().squadOf(player.getUniqueId());
        if (nameArg == null || own.map(s -> s.name().equalsIgnoreCase(nameArg)).orElse(false)) {
            if (own.isEmpty()) {
                messages().send(player, "squad.not-in-squad");
                return null;
            }
            if (configure && !SquadSequenceController.canManage(player, own.get())) {
                messages().send(player, "squad.leader-only");
                return null;
            }
            return new Target(own.get().profile(), own.get());
        }
        if (!SquadSequenceController.isAdmin(player)) {
            messages().send(player, "squad.admin-only");
            return null;
        }
        Optional<SquadProfile> profile = squads().profile(nameArg);
        if (profile.isEmpty()) {
            messages().send(player, "squad.no-squad-named", ph("name", nameArg));
            return null;
        }
        return new Target(profile.get(), squads().squadByName(nameArg).orElse(null));
    }

    /** Settings must not change under a running sequence. */
    private boolean busy(Player player, Target target) {
        if (target.live() != null && plugin.sequenceService().scheduler().findBySquad(target.live().id()).isPresent()) {
            messages().send(player, "squad.busy-no-change");
            return true;
        }
        return false;
    }

    private SyncEquipSettings settings() {
        return plugin.settings();
    }

    // ------------------------------------------------------------------ info

    private void info(Player player, String nameArg) {
        Target target = resolve(player, nameArg, false);
        if (target == null) {
            return;
        }
        SquadProfile profile = target.profile();
        Squad live = target.live();
        SyncEquipSettings s = settings();
        messages().sendPlain(player, "info.header", ph("squad", profile.name()));
        if (live != null) {
            Player leader = plugin.getServer().getPlayer(live.leader());
            messages().sendPlain(player, "info.members", ph("count", live.size()), ph("max", s.maxSquadSize()),
                    ph("leader", leader != null ? leader.getName() : String.valueOf(
                            plugin.getServer().getOfflinePlayer(live.leader()).getName())));
        } else {
            messages().sendPlain(player, "info.inactive");
        }
        int speed = profile.speed() != null ? profile.speed() : s.intervalTicks();
        messages().sendPlain(player, "info.speed", ph("ticks", speed), ph("ms", speed * 50),
                ph("source", profile.speed() != null ? "squad setting" : "default"));
        Loadout loadout = profile.loadout() != null ? profile.loadout() : s.defaultLoadout();
        messages().sendPlain(player, "info.loadout", ph("steps", describeSteps(loadout)),
                ph("source", profile.loadout() != null ? "squad setting" : "default"));
        messages().sendPlain(player, "info.pad", ph("pad", padSummary(profile)));
    }

    private String describeSteps(Loadout loadout) {
        List<String> parts = new ArrayList<>();
        for (EquipmentStep step : loadout.steps()) {
            parts.add(loadout.label(step) + (loadout.requirement(step).optional() ? "?" : ""));
        }
        return parts.isEmpty() ? "nothing" : String.join(" > ", parts);
    }

    private String padSummary(SquadProfile profile) {
        Pad pad = profile.pad();
        if (pad == null) {
            return "none";
        }
        boolean worldLoaded = plugin.getServer().getWorld(pad.worldName()) != null;
        return pad.worldName() + " " + pad.x() + " " + pad.y() + " " + pad.z()
                + (profile.padEnabled() ? "" : " (disabled)") + (worldLoaded ? "" : " (world not loaded)");
    }

    // ------------------------------------------------------------------ speed

    private void speed(Player player, String[] args) {
        Target target = resolve(player, null, args.length > 2);
        if (target == null) {
            return;
        }
        SquadProfile profile = target.profile();
        int global = settings().intervalTicks();
        if (args.length < 3) {
            int ticks = profile.speed() != null ? profile.speed() : global;
            messages().send(player, "speed.current", ph("ticks", ticks), ph("ms", ticks * 50),
                    ph("source", profile.speed() != null ? "squad setting" : "default"));
            return;
        }
        if (busy(player, target)) {
            return;
        }
        if (args[2].equalsIgnoreCase("default") || args[2].equalsIgnoreCase("reset")) {
            squads().setSpeed(profile, null);
            messages().send(player, "speed.reset", ph("ticks", global), ph("ms", global * 50));
            return;
        }
        Integer ticks = parseInt(args[2]);
        if (ticks == null || ticks < SequenceTimeline.MIN_INTERVAL || ticks > SequenceTimeline.MAX_INTERVAL) {
            messages().send(player, "speed.invalid");
            return;
        }
        squads().setSpeed(profile, ticks);
        messages().send(player, "speed.set", ph("ticks", ticks), ph("ms", ticks * 50));
    }

    // ------------------------------------------------------------------ pad

    private void pad(Player player, String[] args) {
        if (args.length < 3) {
            messages().send(player, "pad.usage");
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        String nameArg = args.length > 3 ? args[3] : null;
        PadService pads = plugin.pads();
        switch (action) {
            case "set" -> {
                Target target = resolve(player, nameArg, true);
                if (target == null) {
                    return;
                }
                switch (pads.set(player, target.profile())) {
                    case OK -> {
                        Pad pad = target.profile().pad();
                        messages().send(player, "pad.set", ph("squad", target.profile().name()),
                                ph("where", pad.worldName() + " " + pad.x() + " " + pad.y() + " " + pad.z()));
                    }
                    case NOT_SOLID -> messages().send(player, "pad.not-solid");
                    case CONFLICT -> messages().send(player, "pad.conflict");
                }
            }
            case "remove" -> {
                Target target = resolve(player, nameArg, true);
                if (target == null) {
                    return;
                }
                messages().send(player, squads().removePad(target.profile()) ? "pad.removed" : "pad.none",
                        ph("squad", target.profile().name()));
            }
            case "enable", "disable" -> {
                Target target = resolve(player, nameArg, true);
                if (target == null) {
                    return;
                }
                if (target.profile().pad() == null) {
                    messages().send(player, "pad.none", ph("squad", target.profile().name()));
                    return;
                }
                boolean on = action.equals("enable");
                squads().setPadEnabled(target.profile(), on);
                messages().send(player, on ? "pad.enabled" : "pad.disabled", ph("squad", target.profile().name()));
            }
            case "info" -> {
                Target target = resolve(player, nameArg, false);
                if (target == null) {
                    return;
                }
                if (target.profile().pad() == null) {
                    messages().send(player, "pad.none", ph("squad", target.profile().name()));
                    return;
                }
                messages().send(player, "pad.info", ph("squad", target.profile().name()),
                        ph("where", padSummary(target.profile())));
            }
            default -> messages().send(player, "pad.usage");
        }
    }

    // ------------------------------------------------------------------ loadout

    private void loadout(Player player, String[] args) {
        if (args.length < 3) {
            messages().send(player, "loadout.usage");
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        Target target = resolve(player, null, !action.equals("show"));
        if (target == null) {
            return;
        }
        SquadProfile profile = target.profile();
        Loadout current = profile.loadout() != null ? profile.loadout() : settings().defaultLoadout();
        switch (action) {
            case "show" -> showLoadout(player, profile, current);
            case "reset" -> {
                if (!busy(player, target)) {
                    squads().setLoadout(profile, null);
                    messages().send(player, "loadout.reset");
                }
            }
            case "set" -> {
                if (args.length < 5) {
                    messages().send(player, "loadout.usage-set");
                    return;
                }
                Optional<EquipmentStep> step = EquipmentStep.parse(args[3]);
                if (step.isEmpty()) {
                    messages().send(player, "loadout.unknown-slot", ph("slot", args[3]));
                    return;
                }
                SlotRequirement.Parsed parsed = SlotRequirement.parse(step.get(), args[4]);
                if (!parsed.ok()) {
                    messages().send(player, "loadout.invalid", ph("reason", parsed.error()));
                    return;
                }
                if (busy(player, target)) {
                    return;
                }
                boolean optional = (args.length > 5 && args[5].equalsIgnoreCase("optional"))
                        || (args.length <= 5 && current.requirement(step.get()).optional());
                squads().setLoadout(profile, current.with(step.get(), parsed.requirement().withOptional(optional)));
                messages().send(player, "loadout.set", ph("slot", step.get().displayName()),
                        ph("value", parsed.requirement().token()));
            }
            case "optional" -> {
                Optional<EquipmentStep> step = args.length > 3 ? EquipmentStep.parse(args[3]) : Optional.empty();
                Boolean on = args.length > 4 ? parseBoolean(args[4]) : null;
                if (step.isEmpty() || on == null) {
                    messages().send(player, "loadout.usage-optional");
                    return;
                }
                if (busy(player, target)) {
                    return;
                }
                squads().setLoadout(profile, current.with(step.get(), current.requirement(step.get()).withOptional(on)));
                messages().send(player, on ? "loadout.optional-on" : "loadout.optional-off",
                        ph("slot", step.get().displayName()));
            }
            case "order" -> {
                List<EquipmentStep> order = new ArrayList<>();
                for (int i = 3; i < args.length; i++) {
                    Optional<EquipmentStep> step = EquipmentStep.parse(args[i]);
                    if (step.isEmpty()) {
                        messages().send(player, "loadout.unknown-slot", ph("slot", args[i]));
                        return;
                    }
                    order.add(step.get());
                }
                if (order.isEmpty()) {
                    messages().send(player, "loadout.usage-order");
                    return;
                }
                if (busy(player, target)) {
                    return;
                }
                Loadout updated = current.withOrder(order);
                squads().setLoadout(profile, updated);
                messages().send(player, "loadout.order-set", ph("steps", describeSteps(updated)));
            }
            default -> messages().send(player, "loadout.usage");
        }
    }

    private void showLoadout(Player player, SquadProfile profile, Loadout loadout) {
        messages().sendPlain(player, "loadout.header", ph("squad", profile.name()),
                ph("source", profile.loadout() != null ? "squad setting" : "default"));
        for (EquipmentStep step : loadout.order()) {
            SlotRequirement req = loadout.requirement(step);
            messages().sendPlain(player, "loadout.line", ph("slot", step.displayName().equals("Shield") ? "Offhand"
                    : step.displayName()), ph("value", req.token()), ph("flags", req.optional() ? " (optional)" : ""));
        }
        messages().sendPlain(player, "loadout.order", ph("steps", describeSteps(loadout)));
    }

    // ------------------------------------------------------------------ disband

    private void disband(Player player, String nameArg) {
        Target target = resolve(player, nameArg, true);
        if (target == null) {
            return;
        }
        String name = target.profile().name();
        if (target.live() != null) {
            plugin.sequenceService().scheduler().findBySquad(target.live().id())
                    .ifPresent(sequence -> sequence.cancel(FinishReason.CANCELLED));
        }
        List<UUID> released = squads().disband(name);
        for (UUID id : released) {
            Player member = plugin.getServer().getPlayer(id);
            if (member != null) {
                messages().send(member, "squad.disbanded", ph("squad", name));
            }
        }
        if (!released.contains(player.getUniqueId())) {
            messages().send(player, "squad.disbanded", ph("squad", name));
        }
    }

    // ------------------------------------------------------------------ tab completion

    /** Completes everything after {@code squad <sub>}. {@code args} is the full argument array. */
    public List<String> complete(CommandSender sender, String[] args) {
        String sub = args[1].toLowerCase(Locale.ROOT);
        boolean admin = SquadSequenceController.isAdmin(sender);
        if (args.length == 3) {
            return switch (sub) {
                case "speed" -> filter(Stream.of("1", "2", "4", "6", "10", "default"), args[2]);
                case "pad" -> filter(PAD_ACTIONS.stream(), args[2]);
                case "loadout" -> filter(LOADOUT_ACTIONS.stream(), args[2]);
                case "info", "disband" -> admin ? filter(squadNames(), args[2]) : List.of();
                default -> List.of();
            };
        }
        if (sub.equals("pad") && args.length == 4 && admin) {
            return filter(squadNames(), args[3]);
        }
        if (sub.equals("loadout")) {
            String action = args[2].toLowerCase(Locale.ROOT);
            if (args.length == 4 && (action.equals("set") || action.equals("optional"))) {
                return filter(SLOTS.stream(), args[3]);
            }
            if (action.equals("set") && args.length == 5) {
                return filter(valuesFor(args[3], args[4]), args[4]);
            }
            if (action.equals("set") && args.length == 6) {
                return filter(Stream.of("optional"), args[5]);
            }
            if (action.equals("optional") && args.length == 5) {
                return filter(Stream.of("on", "off"), args[4]);
            }
            if (action.equals("order") && args.length >= 4) {
                return filter(SLOTS.stream().filter(s -> !List.of(args).subList(3, args.length - 1).contains(s)),
                        args[args.length - 1]);
            }
        }
        return List.of();
    }

    private Stream<String> squadNames() {
        return squads().profiles().stream().map(SquadProfile::name);
    }

    private Stream<String> valuesFor(String slotWord, String typed) {
        Optional<EquipmentStep> step = EquipmentStep.parse(slotWord);
        List<String> values = new ArrayList<>(List.of("none", "any"));
        if (step.isEmpty()) {
            return values.stream();
        }
        switch (step.get()) {
            case MAIN_HAND -> values.addAll(List.of("any_sword", "any_axe", "netherite_sword", "diamond_sword",
                    "iron_sword", "netherite_axe", "trident", "bow", "crossbow"));
            case SHIELD -> values.addAll(List.of("shield", "totem_of_undying", "ender_pearl"));
            default -> values.addAll(List.of("netherite", "diamond", "iron", "gold", "chainmail", "leather"));
        }
        if (typed.length() >= 2) {
            String lower = typed.toLowerCase(Locale.ROOT);
            Stream.of(Material.values())
                    .filter(m -> !m.isLegacy() && m.isItem())
                    .map(m -> m.name().toLowerCase(Locale.ROOT))
                    .filter(name -> name.startsWith(lower) && (step.get().isHand() || step.get().matches(Material.matchMaterial(name))))
                    .limit(40)
                    .forEach(values::add);
        }
        return values.stream().distinct();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).forEach(result::add);
        return result;
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBoolean(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> Boolean.TRUE;
            case "off", "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }
}
