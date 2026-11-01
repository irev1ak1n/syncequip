package dev.syncequip.commands;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.SyncEquipPlugin;
import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.demo.DemoManager;
import dev.syncequip.equipment.SequenceService;
import dev.syncequip.squad.Squad;
import dev.syncequip.squad.SquadManager;
import dev.syncequip.squad.SquadSequenceController;
import dev.syncequip.synchronization.RemovalReason;
import dev.syncequip.util.MessageUtil;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.bukkit.Difficulty;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SyncEquipCommand implements BasicCommand {

    public static final String PERMISSION_USE = "syncequip.use";
    public static final String PERMISSION_SQUAD = "syncequip.squad";
    public static final String PERMISSION_RELOAD = "syncequip.reload";
    public static final String PERMISSION_DEMO = "syncequip.demo";

    private static final List<String> ROOT = List.of("solo", "squad", "demo", "settings", "reload", "help");
    private static final List<String> DEMO = List.of("spawn", "start", "repeat", "stop", "reset", "clear", "speed");
    private static final List<String> SQUAD = List.of("create", "invite", "join", "leave", "list", "start",
            "cancel", "transfer");

    private final SyncEquipPlugin plugin;

    public SyncEquipCommand(SyncEquipPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        handle(source.getSender(), args);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return complete(source.getSender(), args);
    }

    public void handle(CommandSender sender, String[] args) {
        MessageUtil messages = plugin.messages();
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "solo" -> solo(sender);
            case "squad" -> squad(sender, args);
            case "demo" -> demo(sender, args);
            case "settings" -> settings(sender);
            case "reload" -> reload(sender);
            case "help" -> sendHelp(sender);
            default -> messages.send(sender, "command.unknown", ph("input", args[0]));
        }
    }


    private void solo(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, PERMISSION_USE)) {
            return;
        }
        plugin.sequenceService().startSolo(player);
    }

    private void settings(CommandSender sender) {
        if (!requirePermission(sender, PERMISSION_USE)) {
            return;
        }
        SyncEquipSettings s = plugin.settings();
        MessageUtil messages = plugin.messages();
        messages.sendPlain(sender, "settings.header");
        line(sender, "Interval", s.intervalTicks() + " ticks (~" + (s.intervalTicks() * 50) + " ms)");
        line(sender, "Order", SequenceService.describe(s.order()));
        line(sender, "Require empty slots", String.valueOf(s.requireEmptySlots()));
        line(sender, "Missing item policy", s.missingItemPolicy().name());
        line(sender, "Solo countdown", s.soloCountdownSeconds() + " s");
        line(sender, "Squad countdown", s.squadCountdownSeconds() + " s");
        line(sender, "Max squad size", String.valueOf(s.maxSquadSize()));
        line(sender, "Custom sounds", String.valueOf(s.sounds().enabled()));
        line(sender, "Particles", String.valueOf(s.visuals().particlesEnabled()));
        line(sender, "Sneak + swap trigger", s.controls().sneakSwapTrigger()
                ? "enabled (" + s.controls().sneakSwapAction() + ")" : "disabled");
        line(sender, "Cooldown", s.controls().cooldownSeconds() + " s");
    }

    private void line(CommandSender sender, String name, String value) {
        plugin.messages().sendPlain(sender, "settings.line", ph("name", name), ph("value", value));
    }

    private void reload(CommandSender sender) {
        if (!requirePermission(sender, PERMISSION_RELOAD)) {
            return;
        }
        List<String> warnings = plugin.reloadSettings();
        if (warnings.isEmpty()) {
            plugin.messages().send(sender, "reload.success");
        } else {
            plugin.messages().send(sender, "reload.warnings", ph("count", warnings.size()));
            for (String warning : warnings) {
                plugin.messages().sendPlain(sender, "reload.warning-line", ph("warning", warning));
            }
        }
    }


    private void demo(CommandSender sender, String[] args) {
        if (!requirePermission(sender, PERMISSION_DEMO)) {
            return;
        }
        MessageUtil messages = plugin.messages();
        DemoManager demo = plugin.demo();
        if (args.length < 2) {
            messages.send(sender, "demo.usage");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "spawn" -> demoSpawn(sender, args, demo);
            case "start" -> demoStart(sender, demo, 1);
            case "repeat" -> {
                int max = DemoManager.MAX_REPEATS;
                Integer times = args.length > 2 ? parseInt(args[2]) : null;
                if (times == null || times < 1 || times > max) {
                    messages.send(sender, "demo.repeat-invalid", ph("max", max));
                } else {
                    demoStart(sender, demo, times);
                }
            }
            case "stop" -> messages.send(sender, demo.stop() ? "demo.stopping" : "demo.nothing-running");
            case "reset" -> messages.send(sender, "demo.reset", ph("count", demo.reset()));
            case "clear" -> messages.send(sender, "demo.cleared", ph("count", demo.clear()));
            case "speed" -> {
                Integer ticks = args.length > 2 ? parseInt(args[2]) : null;
                if (ticks == null || ticks < 1 || ticks > 10) {
                    messages.send(sender, "demo.speed-invalid", ph("current", demo.interval()));
                } else {
                    demo.setInterval(ticks);
                    messages.send(sender, "demo.speed-set", ph("ticks", ticks), ph("ms", ticks * 50));
                }
            }
            default -> messages.send(sender, "demo.usage");
        }
    }

    private void demoSpawn(CommandSender sender, String[] args, DemoManager demo) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        int max = plugin.settings().demo().maxMobs();
        Integer count = args.length > 2 ? parseInt(args[2]) : null;
        if (count == null || count < 1 || count > max) {
            plugin.messages().send(player, "demo.count-invalid", ph("max", max));
            return;
        }
        if (player.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
            plugin.messages().send(player, "demo.peaceful");
            return;
        }
        int spawned = demo.spawn(player, count);
        plugin.messages().send(player, spawned == count ? "demo.spawned" : "demo.spawn-short",
                ph("count", spawned), ph("wanted", count));
    }

    private void demoStart(CommandSender sender, DemoManager demo, int runs) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        MessageUtil messages = plugin.messages();
        switch (demo.start(player, runs)) {
            case NO_MOBS -> messages.send(player, "demo.no-mobs");
            case BUSY -> messages.send(player, "demo.busy");
            case STARTED -> messages.send(player, runs > 1 ? "demo.repeat-started" : "demo.started",
                    ph("count", demo.mobCount()), ph("ticks", demo.interval()), ph("runs", runs));
        }
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void squad(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, PERMISSION_SQUAD)) {
            return;
        }
        MessageUtil messages = plugin.messages();
        if (args.length < 2) {
            messages.send(player, "command.squad-usage");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> squadCreate(player);
            case "invite" -> withTarget(player, args, "invite", target -> squadInvite(player, target));
            case "join" -> withTarget(player, args, "join", target -> squadJoin(player, target));
            case "leave" -> squadLeave(player);
            case "list" -> squadList(player);
            case "start" -> plugin.squadController().start(player);
            case "cancel" -> plugin.squadController().cancel(player);
            case "transfer" -> withTarget(player, args, "transfer", target -> squadTransfer(player, target));
            default -> messages.send(player, "command.squad-usage");
        }
    }

    private void withTarget(Player player, String[] args, String sub, Consumer<Player> action) {
        if (args.length < 3) {
            plugin.messages().send(player, "command.missing-player", ph("sub", sub));
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null) {
            plugin.messages().send(player, "command.player-not-found", ph("player", args[2]));
            return;
        }
        action.accept(target);
    }

    private void squadCreate(Player player) {
        SquadManager.Status status = plugin.squads().create(player.getUniqueId());
        if (status == SquadManager.Status.OK) {
            plugin.messages().send(player, "squad.created");
        } else {
            plugin.messages().send(player, "squad.already-in-squad");
        }
    }

    private void squadInvite(Player leader, Player target) {
        SyncEquipSettings s = plugin.settings();
        MessageUtil messages = plugin.messages();
        SquadManager.Status status = plugin.squads().invite(leader.getUniqueId(), target.getUniqueId(),
                s.maxSquadSize(), s.inviteExpirySeconds() * 1000L);
        switch (status) {
            case OK, TARGET_IN_OTHER_SQUAD -> {
                messages.send(leader, "squad.invite-sent", ph("player", target.getName()),
                        ph("seconds", s.inviteExpirySeconds()));
                messages.send(target, "squad.invite-received", ph("leader", leader.getName()),
                        ph("seconds", s.inviteExpirySeconds()));
                if (status == SquadManager.Status.TARGET_IN_OTHER_SQUAD) {
                    messages.send(target, "squad.invite-leave-first");
                }
            }
            case NOT_IN_SQUAD -> messages.send(leader, "squad.not-in-squad");
            case NOT_LEADER -> messages.send(leader, "squad.leader-only");
            case SELF -> messages.send(leader, "squad.not-yourself");
            case TARGET_ALREADY_MEMBER -> messages.send(leader, "squad.already-member", ph("player", target.getName()));
            case SQUAD_FULL -> messages.send(leader, "squad.full", ph("max", s.maxSquadSize()));
            default -> messages.send(leader, "command.failed");
        }
    }

    private void squadJoin(Player player, Player leader) {
        MessageUtil messages = plugin.messages();
        SquadManager.Status status = plugin.squads().join(player.getUniqueId(), leader.getUniqueId(),
                plugin.settings().maxSquadSize());
        switch (status) {
            case OK -> {
                Squad squad = plugin.squads().squadOf(player.getUniqueId()).orElseThrow();
                broadcast(squad, "squad.member-joined", player.getName());
            }
            case ALREADY_IN_SQUAD -> messages.send(player, "squad.already-in-squad");
            case NO_SUCH_SQUAD -> messages.send(player, "squad.no-such-squad", ph("player", leader.getName()));
            case NOT_INVITED -> messages.send(player, "squad.not-invited", ph("player", leader.getName()));
            case SQUAD_FULL -> messages.send(player, "squad.full", ph("max", plugin.settings().maxSquadSize()));
            default -> messages.send(player, "command.failed");
        }
    }

    private void squadLeave(Player player) {
        MessageUtil messages = plugin.messages();
        if (plugin.squads().squadOf(player.getUniqueId()).isEmpty()) {
            messages.send(player, "squad.not-in-squad");
            return;
        }
        plugin.leaveSquad(player.getUniqueId(), RemovalReason.LEFT_SQUAD);
        messages.send(player, "squad.left");
    }

    private void squadList(Player player) {
        MessageUtil messages = plugin.messages();
        Optional<Squad> found = plugin.squads().squadOf(player.getUniqueId());
        if (found.isEmpty()) {
            messages.send(player, "squad.not-in-squad");
            return;
        }
        Squad squad = found.get();
        messages.sendPlain(player, "squad.list-header", ph("count", squad.size()),
                ph("max", plugin.settings().maxSquadSize()));
        Server server = plugin.getServer();
        for (UUID id : squad.members()) {
            Player member = server.getPlayer(id);
            String name = member != null ? member.getName() : String.valueOf(server.getOfflinePlayer(id).getName());
            String key = squad.isLeader(id) ? "squad.list-leader" : "squad.list-member";
            messages.sendPlain(player, key, ph("player", name));
        }
    }

    private void squadTransfer(Player leader, Player target) {
        MessageUtil messages = plugin.messages();
        SquadManager.Status status = plugin.squads().transfer(leader.getUniqueId(), target.getUniqueId());
        switch (status) {
            case OK -> broadcast(plugin.squads().squadOf(leader.getUniqueId()).orElseThrow(),
                    "squad.leader-changed", target.getName());
            case NOT_IN_SQUAD -> messages.send(leader, "squad.not-in-squad");
            case NOT_LEADER -> messages.send(leader, "squad.leader-only");
            case SELF -> messages.send(leader, "squad.not-yourself");
            case TARGET_NOT_MEMBER -> messages.send(leader, "squad.target-not-member", ph("player", target.getName()));
            default -> messages.send(leader, "command.failed");
        }
    }

    private void broadcast(Squad squad, String key, String playerName) {
        for (UUID id : squad.members()) {
            Player member = plugin.getServer().getPlayer(id);
            if (member != null) {
                plugin.messages().send(member, key, ph("player", playerName));
            }
        }
    }


    private void sendHelp(CommandSender sender) {
        MessageUtil messages = plugin.messages();
        messages.sendPlain(sender, "help.header");
        messages.sendList(sender, "help.lines");
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            return filter(ROOT.stream().filter(sub -> canSee(sender, sub)), prefix);
        }
        if (args[0].equalsIgnoreCase("demo") && sender.hasPermission(PERMISSION_DEMO)) {
            if (args.length == 2) {
                return filter(DEMO.stream(), args[1]);
            }
            if (args.length == 3) {
                return switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "spawn" -> filter(Stream.of("1", "5", "10", "20"), args[2]);
                    case "speed" -> filter(Stream.of("1", "2", "4", "6", "10"), args[2]);
                    case "repeat" -> filter(Stream.of("2", "3", "5", "10"), args[2]);
                    default -> List.of();
                };
            }
            return List.of();
        }
        if (!args[0].equalsIgnoreCase("squad") || !sender.hasPermission(PERMISSION_SQUAD)) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(SQUAD.stream(), args[1]);
        }
        if (args.length == 3) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("invite") || sub.equals("join") || sub.equals("transfer")) {
                return filter(onlineNames(sender, sub), args[2]);
            }
        }
        return List.of();
    }

    private Stream<String> onlineNames(CommandSender sender, String sub) {
        Stream<? extends Player> players = plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> !p.equals(sender));
        if (sender instanceof Player player) {
            Optional<Squad> squad = plugin.squads().squadOf(player.getUniqueId());
            if (sub.equals("transfer") && squad.isPresent()) {
                players = players.filter(p -> squad.get().isMember(p.getUniqueId()));
            } else if (sub.equals("join")) {
                players = players.filter(p -> plugin.squads().squadOf(p.getUniqueId())
                        .map(s -> s.isLeader(p.getUniqueId())).orElse(false));
            }
        }
        return players.map(Player::getName);
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).forEach(result::add);
        return result;
    }

    private static boolean canSee(CommandSender sender, String sub) {
        return switch (sub) {
            case "reload" -> sender.hasPermission(PERMISSION_RELOAD);
            case "squad" -> sender.hasPermission(PERMISSION_SQUAD);
            case "demo" -> sender.hasPermission(PERMISSION_DEMO);
            default -> true;
        };
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "command.players-only");
        return null;
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, "command.no-permission");
        return false;
    }
}
