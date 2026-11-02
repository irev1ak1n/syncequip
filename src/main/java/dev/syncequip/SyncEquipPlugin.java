package dev.syncequip;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.audio.EquipmentSoundManager;
import dev.syncequip.commands.SquadConfigCommands;
import dev.syncequip.commands.SyncEquipCommand;
import dev.syncequip.config.ConfigManager;
import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.demo.DemoListener;
import dev.syncequip.demo.DemoManager;
import dev.syncequip.equipment.EquipmentManager;
import dev.syncequip.equipment.SequenceService;
import dev.syncequip.listeners.HotkeyListener;
import dev.syncequip.listeners.JoinBlockListener;
import dev.syncequip.listeners.PlayerListener;
import dev.syncequip.squad.JoinBlockService;
import dev.syncequip.squad.Squad;
import dev.syncequip.squad.SquadManager;
import dev.syncequip.squad.SquadSequenceController;
import dev.syncequip.squad.SquadStore;
import dev.syncequip.synchronization.FinishReason;
import dev.syncequip.synchronization.RemovalReason;
import dev.syncequip.synchronization.TickSequenceScheduler;
import dev.syncequip.util.CooldownTracker;
import dev.syncequip.util.MessageUtil;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class SyncEquipPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageUtil messages;
    private TickSequenceScheduler scheduler;
    private SequenceService sequenceService;
    private SquadManager squads;
    private SquadSequenceController squadController;
    private DemoManager demo;
    private JoinBlockService joinBlocks;
    private SquadStore squadStore;
    private SquadConfigCommands squadConfig;
    private boolean saveScheduled;
    private CooldownTracker cooldowns;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();
        messages = new MessageUtil(this);
        messages.load();

        scheduler = new TickSequenceScheduler(getLogger());
        cooldowns = new CooldownTracker(System::currentTimeMillis);
        squads = new SquadManager(System::currentTimeMillis);
        sequenceService = new SequenceService(getServer(), getLogger(), scheduler, this::settings,
                new EquipmentManager(), new EquipmentSoundManager(), messages, cooldowns, Bukkit::getCurrentTick);
        squadController = new SquadSequenceController(getServer(), squads, sequenceService, messages, this::settings);

        squadStore = new SquadStore(getDataFolder().toPath().resolve("squads.yml"), getLogger());
        List<String> squadWarnings = new java.util.ArrayList<>();
        squads.loadProfiles(squadStore.load(squadWarnings));
        squadWarnings.forEach(w -> getLogger().warning(w));
        joinBlocks = new JoinBlockService(getServer(), squads, messages, this::settings, System::currentTimeMillis);
        joinBlocks.reindex();
        squads.setChangeListener(this::markSquadsDirty);
        squadConfig = new SquadConfigCommands(this);

        demo = new DemoManager(this, scheduler, this::settings, new EquipmentSoundManager(), messages, Bukkit::getCurrentTick);
        demo.removeTaggedMobs();

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinBlockListener(joinBlocks, squads), this);
        getServer().getPluginManager().registerEvents(new DemoListener(demo), this);
        getServer().getPluginManager().registerEvents(new HotkeyListener(this), this);
        registerCommand("syncequip", "Synchronized armor equipping", List.of("se", "sequip"),
                new SyncEquipCommand(this));

        // This is the only timer in the plugin. Every sequence on the server advances from this one task.
        tickTask = getServer().getScheduler().runTaskTimer(this, () -> scheduler.tick(Bukkit.getCurrentTick()), 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (demo != null) {
            demo.shutdown();
        }
        if (scheduler != null) {
            // Each step is an atomic move, so stopping here always leaves every inventory in a valid state.
            scheduler.cancelAll(FinishReason.SHUTDOWN);
        }
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (squads != null && squadStore != null) {
            squadStore.write(squadStore.serialize(squads.profiles()));
            squads.setChangeListener(() -> { });
            squads.clear();
        }
    }

    /** Saves squad settings soon. Many changes in a row (a leader tuning a loadout) become one write. */
    private void markSquadsDirty() {
        if (saveScheduled || !isEnabled()) {
            return;
        }
        saveScheduled = true;
        getServer().getScheduler().runTaskLater(this, () -> {
            saveScheduled = false;
            flushSquads(false);
        }, 20L);
    }

    /** Writes squads.yml. The text is built on the main thread, the file write can happen off it. */
    public void flushSquads(boolean wait) {
        String contents = squadStore.serialize(squads.profiles());
        if (!squadStore.exists() && squads.profiles().stream().noneMatch(p -> p.persistable())) {
            return;
        }
        if (wait || !isEnabled()) {
            squadStore.write(contents);
        } else {
            getServer().getScheduler().runTaskAsynchronously(this, () -> squadStore.write(contents));
        }
    }

    public List<String> reloadSettings() {
        List<String> warnings = configManager.load();
        messages.load();
        return warnings;
    }

    public void leaveSquad(UUID playerId, RemovalReason reason) {
        Squad squad = squads.squadOf(playerId).orElse(null);
        if (squad == null) {
            return;
        }
        scheduler.findBySquad(squad.id()).ifPresent(sequence -> sequence.removeParticipant(playerId, reason));
        Player leaving = getServer().getPlayer(playerId);
        String name = leaving != null ? leaving.getName() : String.valueOf(getServer().getOfflinePlayer(playerId).getName());
        SquadManager.LeaveResult result = squads.leave(playerId);
        if (result.disbanded()) {
            return;
        }
        for (UUID id : result.squad().members()) {
            Player member = getServer().getPlayer(id);
            if (member == null) {
                continue;
            }
            messages.send(member, "squad.member-left", ph("player", name));
            if (result.newLeader() != null) {
                Player newLeader = getServer().getPlayer(result.newLeader());
                messages.send(member, "squad.leader-changed",
                        ph("player", newLeader != null ? newLeader.getName() : "?"));
            }
        }
    }

    public SyncEquipSettings settings() {
        return configManager.settings();
    }

    public MessageUtil messages() {
        return messages;
    }

    public SequenceService sequenceService() {
        return sequenceService;
    }

    public SquadManager squads() {
        return squads;
    }

    public SquadSequenceController squadController() {
        return squadController;
    }

    public JoinBlockService joinBlocks() {
        return joinBlocks;
    }

    public SquadConfigCommands squadConfig() {
        return squadConfig;
    }

    public DemoManager demo() {
        return demo;
    }

    public CooldownTracker cooldowns() {
        return cooldowns;
    }
}
