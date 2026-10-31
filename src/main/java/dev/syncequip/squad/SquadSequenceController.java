package dev.syncequip.squad;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.config.SyncEquipSettings;
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
import org.bukkit.entity.Player;

public final class SquadSequenceController {

    public static final String PERMISSION_START = "syncequip.squad.start";
    public static final String PERMISSION_ADMIN = "syncequip.admin";

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

    public static boolean canControl(Player player, Squad squad) {
        return (squad.isLeader(player.getUniqueId()) && player.hasPermission(PERMISSION_START))
                || player.hasPermission(PERMISSION_ADMIN);
    }

    public boolean start(Player requester) {
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
        if (sequences.scheduler().findBySquad(squad.id()).isPresent()) {
            messages.send(requester, "squad.already-running");
            return false;
        }

        SyncEquipSettings config = settings.get();
        List<Player> members = new ArrayList<>();
        Map<String, Component> problems = new LinkedHashMap<>();
        for (UUID id : squad.members()) {
            Player member = server.getPlayer(id);
            if (member == null) {
                String name = server.getOfflinePlayer(id).getName();
                problems.put(name == null ? id.toString() : name, messages.render("problem.offline"));
                continue;
            }
            sequences.checkReady(member, config).ifPresentOrElse(
                    reason -> problems.put(member.getName(), reason),
                    () -> members.add(member));
        }
        if (!problems.isEmpty()) {
            messages.send(requester, "squad.start-blocked", ph("count", problems.size()));
            problems.forEach((name, reason) ->
                    messages.sendPlain(requester, "squad.start-blocked-line", ph("player", name), ph("reason", reason)));
            return false;
        }

        sequences.startGroup(SyncSequence.Mode.SQUAD, squad.id(), requester, members,
                config.squadCountdownSeconds(), config);
        for (Player member : members) {
            messages.send(member, "squad.started", ph("leader", requester.getName()), ph("count", members.size()));
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
