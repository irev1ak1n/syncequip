package dev.syncequip.squad;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.equipment.Loadout;
import dev.syncequip.equipment.SequenceService;
import dev.syncequip.synchronization.FinishReason;
import dev.syncequip.synchronization.SyncSequence;
import dev.syncequip.util.MessageUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SquadSequenceController {
    public static final String PERMISSION_START = "syncequip.squad.start";
    public static final String PERMISSION_MANAGE = "syncequip.squad.manage";
    public static final String PERMISSION_ADMIN = "syncequip.admin";

    private static final int MAX_LISTED = 8;

    private final Server server;
    private final SquadManager squads;
    private final SequenceService sequences;
    private final MessageUtil messages;
    private final Supplier<SyncEquipSettings> settings;

    public SquadSequenceController(Server server, SquadManager squads, SequenceService sequences,
                                   MessageUtil messages, Supplier<SyncEquipSettings> settings) {
        this.server = server;
        this.squads = squads;
        this.sequences = sequences;
        this.messages = messages;
        this.settings = settings;
    }

    public static boolean isAdmin(CommandSender sender) {
        return sender.hasPermission(PERMISSION_ADMIN);
    }

    public static boolean canControl(Player player, Squad squad) {
        return (squad.isLeader(player.getUniqueId()) && player.hasPermission(PERMISSION_START)) || isAdmin(player);
    }

    public static boolean canManage(CommandSender sender, Squad squad) {
        if (isAdmin(sender)) {
            return true;
        }
        return sender instanceof Player player && squad.isLeader(player.getUniqueId())
                && player.hasPermission(PERMISSION_MANAGE);
    }

    private Optional<Squad> controlledSquad(Player requester) {
        Optional<Squad> found = squads.squadOf(requester.getUniqueId());
        if (found.isEmpty()) {
            messages.send(requester, "squad.not-in-squad");
            return Optional.empty();
        }
        Squad squad = found.get();
        if (!canControl(requester, squad)) {
            messages.send(requester, "squad.leader-only");
            return Optional.empty();
        }
        if (sequences.scheduler().findBySquad(squad.id()).isPresent()) {
            messages.send(requester, "squad.already-running");
            return Optional.empty();
        }
        return Optional.of(squad);
    }

    public boolean start(Player requester) {
        Optional<Squad> found = controlledSquad(requester);
        if (found.isEmpty()) {
            return false;
        }
        Squad squad = found.get();
        SyncEquipSettings config = settings.get();
        Loadout loadout = squad.effectiveLoadout(config.defaultLoadout());
        int interval = squad.effectiveSpeed(config.intervalTicks());

        List<Player> members = new ArrayList<>();
        Map<String, Component> problems = new LinkedHashMap<>();
        for (UUID id : squad.members()) {
            Player member = server.getPlayer(id);
            if (member == null) {
                continue;
            }
            sequences.checkReady(member, config, loadout, true, false).ifPresentOrElse(
                    reason -> problems.put(member.getName(), reason),
                    () -> members.add(member));
        }
        if (!problems.isEmpty()) {
            messages.send(requester, members.isEmpty() ? "squad.start-blocked" : "squad.start-left-out",
                    ph("count", problems.size()));
            int shown = 0;
            for (Map.Entry<String, Component> entry : problems.entrySet()) {
                if (shown++ == MAX_LISTED) {
                    messages.sendPlain(requester, "squad.more-blocked", ph("count", problems.size() - MAX_LISTED));
                    break;
                }
                messages.sendPlain(requester, "squad.start-blocked-line", ph("player", entry.getKey()),
                        ph("reason", entry.getValue()));
            }
            if (members.isEmpty()) {
                return false;
            }
        }
        if (loadout.steps().isEmpty()) {
            messages.send(requester, "squad.loadout-empty");
            return false;
        }

        sequences.startGroup(SyncSequence.Mode.SQUAD, squad.id(), requester, members, config.squadCountdownSeconds(),
                config, loadout, SequenceService.Action.EQUIP, interval, true);
        for (Player member : members) {
            messages.send(member, "squad.started", ph("leader", requester.getName()), ph("count", members.size()),
                    ph("ticks", interval));
        }
        return true;
    }

    public boolean unequip(Player requester) {
        Optional<Squad> found = controlledSquad(requester);
        if (found.isEmpty()) {
            return false;
        }
        Squad squad = found.get();
        SyncEquipSettings config = settings.get();
        Loadout loadout = squad.effectiveLoadout(config.defaultLoadout());
        int interval = squad.effectiveSpeed(config.intervalTicks());
        if (loadout.steps().isEmpty()) {
            messages.send(requester, "squad.loadout-empty");
            return false;
        }

        List<Player> members = new ArrayList<>();
        Map<String, Component> excluded = new LinkedHashMap<>();
        int nothingToRemove = 0;
        for (UUID id : squad.members()) {
            Player member = server.getPlayer(id);
            if (member == null) {
                continue;
            }
            SequenceService.UnequipCheck check = sequences.checkUnequip(member, loadout);
            if (check.problem() != null) {
                excluded.put(member.getName(), check.problem());
                messages.send(member, "unequip.left-out", ph("reason", check.problem()));
            } else if (check.nothingToRemove()) {
                nothingToRemove++;
            } else {
                members.add(member);
            }
        }
        if (!excluded.isEmpty()) {
            messages.send(requester, "unequip.excluded", ph("count", excluded.size()));
            int shown = 0;
            for (Map.Entry<String, Component> entry : excluded.entrySet()) {
                if (shown++ == MAX_LISTED) {
                    messages.sendPlain(requester, "squad.more-blocked", ph("count", excluded.size() - MAX_LISTED));
                    break;
                }
                messages.sendPlain(requester, "squad.start-blocked-line", ph("player", entry.getKey()),
                        ph("reason", entry.getValue()));
            }
        }
        if (members.isEmpty()) {
            messages.send(requester, "unequip.nobody");
            return false;
        }

        sequences.startGroup(SyncSequence.Mode.SQUAD, squad.id(), requester, members, config.unequipCountdownSeconds(),
                config, loadout, SequenceService.Action.UNEQUIP, interval, true);
        for (Player member : members) {
            messages.send(member, "unequip.started", ph("leader", requester.getName()), ph("count", members.size()),
                    ph("ticks", interval));
        }
        if (nothingToRemove > 0) {
            messages.send(requester, "unequip.skipped-empty", ph("count", nothingToRemove));
        }
        return true;
    }

    public boolean cancel(Player requester) {
        Optional<Squad> found = squads.squadOf(requester.getUniqueId());
        if (found.isEmpty()) {
            messages.send(requester, "squad.not-in-squad");
            return false;
        }
        Squad squad = found.get();
        if (!canControl(requester, squad)) {
            messages.send(requester, "squad.leader-only");
            return false;
        }
        Optional<SyncSequence> running = sequences.scheduler().findBySquad(squad.id());
        if (running.isEmpty()) {
            messages.send(requester, "squad.nothing-to-cancel");
            return false;
        }
        running.get().cancel(FinishReason.CANCELLED);
        return true;
    }
}
