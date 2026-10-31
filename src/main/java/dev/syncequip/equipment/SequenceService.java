package dev.syncequip.equipment;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.audio.EquipmentSoundManager;
import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.synchronization.FinishReason;
import dev.syncequip.synchronization.RemovalReason;
import dev.syncequip.synchronization.SequenceCallbacks;
import dev.syncequip.synchronization.SequenceTimeline;
import dev.syncequip.synchronization.StepOutcome;
import dev.syncequip.synchronization.SyncSequence;
import dev.syncequip.synchronization.TickSequenceScheduler;
import dev.syncequip.util.CooldownTracker;
import dev.syncequip.util.MessageUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.entity.Player;

public final class SequenceService implements SequenceCallbacks {

    public static final String BYPASS_COOLDOWN = "syncequip.bypass.cooldown";

    private final Server server;
    private final Logger logger;
    private final TickSequenceScheduler scheduler;
    private final Supplier<SyncEquipSettings> settings;
    private final EquipmentManager equipmentManager;
    private final EquipmentSoundManager sounds;
    private final MessageUtil messages;
    private final CooldownTracker cooldowns;
    private final LongSupplier currentTick;
    private final Map<Long, SyncEquipSettings> sequenceSettings = new HashMap<>();

    public SequenceService(Server server, Logger logger, TickSequenceScheduler scheduler,
                           Supplier<SyncEquipSettings> settings, EquipmentManager equipmentManager,
                           EquipmentSoundManager sounds, MessageUtil messages, CooldownTracker cooldowns,
                           LongSupplier currentTick) {
        this.server = server;
        this.logger = logger;
        this.scheduler = scheduler;
        this.settings = settings;
        this.equipmentManager = equipmentManager;
        this.sounds = sounds;
        this.messages = messages;
        this.cooldowns = cooldowns;
        this.currentTick = currentTick;
    }


    public Optional<Component> checkReady(Player player, SyncEquipSettings config) {
        if (scheduler.isBusy(player.getUniqueId())) {
            return Optional.of(messages.render("problem.busy"));
        }
        if (!player.hasPermission(BYPASS_COOLDOWN) && cooldowns.isCoolingDown(player.getUniqueId())) {
            return Optional.of(messages.render("problem.cooldown",
                    ph("seconds", cooldowns.remainingSeconds(player.getUniqueId()))));
        }
        if (!InventoryValidator.canEquip(player)) {
            return Optional.of(messages.render("problem.invalid"));
        }
        InventoryValidator.Report report = InventoryValidator.validate(player, config.order(),
                config.requireEmptySlots());
        if (report.acceptableFor(config.missingItemPolicy())) {
            return Optional.empty();
        }
        if (report.noneAvailable() && config.missingItemPolicy() == MissingItemPolicy.SKIP) {
            return Optional.of(messages.render("problem.nothing"));
        }
        List<Component> parts = new ArrayList<>();
        if (!report.missing().isEmpty()) {
            parts.add(messages.render("problem.missing", ph("pieces", describe(report.missing()))));
        }
        if (!report.occupied().isEmpty()) {
            parts.add(messages.render("problem.occupied", ph("pieces", describe(report.occupied()))));
        }
        return Optional.of(Component.join(JoinConfiguration.commas(true), parts));
    }

    public boolean startSolo(Player player) {
        SyncEquipSettings config = settings.get();
        Optional<Component> problem = checkReady(player, config);
        if (problem.isPresent()) {
            messages.send(player, "solo.cannot-start", ph("reason", problem.get()));
            return false;
        }
        startGroup(SyncSequence.Mode.SOLO, null, player, List.of(player), config.soloCountdownSeconds(), config);
        return true;
    }

    public SyncSequence startGroup(SyncSequence.Mode mode, UUID squadId, Player initiator, List<Player> players,
                                   int countdownSeconds, SyncEquipSettings config) {
        SequenceTimeline timeline = new SequenceTimeline(countdownSeconds, config.intervalTicks(), config.order());
        // Every participant shares this one start tick, which is why each step lands on the same server tick for all of them.
        long startTick = currentTick.getAsLong() + 1;
        List<UUID> ids = players.stream().map(Player::getUniqueId).toList();
        SyncSequence sequence = scheduler.schedule(mode, squadId, initiator.getUniqueId(), ids, timeline,
                startTick, this);
        sequenceSettings.put(sequence.id(), config);
        for (Player player : players) {
            cooldowns.start(player.getUniqueId(), config.controls().cooldownSeconds());
        }
        if (config.debugLogTicks()) {
            logger.info(String.format(Locale.ROOT,
                    "[debug] sequence #%d (%s) scheduled: participants=%d startTick=%d firstStepTick=%d interval=%d",
                    sequence.id(), mode, ids.size(), startTick, startTick + timeline.firstStepOffset(),
                    timeline.intervalTicks()));
        }
        return sequence;
    }

    public boolean isLocked(UUID player) {
        return scheduler.findByParticipant(player)
                .filter(s -> s.state() == SyncSequence.State.RUNNING)
                .map(s -> settingsFor(s).lockInventoryDuringSequence())
                .orElse(false);
    }

    public TickSequenceScheduler scheduler() {
        return scheduler;
    }


    @Override
    public void onCountdown(SyncSequence sequence, int secondsRemaining, long tick) {
        SyncEquipSettings config = settingsFor(sequence);
        for (Player player : onlineParticipants(sequence)) {
            switch (config.visuals().countdownDisplay()) {
                case TITLE -> messages.title(player, "sequence.countdown-title", "sequence.countdown-subtitle",
                        ph("seconds", secondsRemaining));
                case ACTIONBAR -> messages.actionBar(player, "sequence.countdown-actionbar",
                        ph("seconds", secondsRemaining));
                case NONE -> {
                }
            }
            if (config.sounds().countdownSoundEnabled()) {
                sounds.playCue(player, config.sounds().countdownSound(), 0.6f, 1.0f);
            }
        }
    }

    @Override
    public void onBeforeFirstStep(SyncSequence sequence, long tick) {
        SyncEquipSettings config = settingsFor(sequence);
        // Inventories can change during the countdown, so everyone is checked again just before the first item moves.
        for (UUID id : List.copyOf(sequence.participants())) {
            Player player = server.getPlayer(id);
            if (player == null) {
                sequence.removeParticipant(id, RemovalReason.DISCONNECTED);
                continue;
            }
            if (!InventoryValidator.canEquip(player)) {
                sequence.removeParticipant(id, RemovalReason.INVALID_STATE);
                continue;
            }
            InventoryValidator.Report report = InventoryValidator.validate(player, config.order(),
                    config.requireEmptySlots());
            if (!report.acceptableFor(config.missingItemPolicy())) {
                sequence.removeParticipant(id, RemovalReason.ITEM_UNAVAILABLE);
            }
        }
        if (sequence.isFinished()) {
            return;
        }
        for (Player player : onlineParticipants(sequence)) {
            if (sequence.timeline().countdownSeconds() > 0
                    && config.visuals().countdownDisplay() == SyncEquipSettings.CountdownDisplay.TITLE) {
                player.clearTitle();
            }
            if (config.sounds().goSoundEnabled()) {
                sounds.playCue(player, config.sounds().goSound(), 0.7f, 1.0f);
            }
        }
    }

    @Override
    public StepOutcome executeStep(SyncSequence sequence, UUID participant, EquipmentStep step, int stepIndex,
                                   long scheduledTick, long actualTick) {
        SyncEquipSettings config = settingsFor(sequence);
        Player player = server.getPlayer(participant);
        if (player == null) {
            sequence.removeParticipant(participant, RemovalReason.DISCONNECTED);
            return StepOutcome.SKIPPED;
        }
        // Equipping is silent when custom sounds are on, so each step plays exactly one sound and never two.
        EquipmentManager.Result result = equipmentManager.equip(player, step, config.requireEmptySlots(),
                config.sounds().enabled());
        StepOutcome outcome = switch (result.status()) {
            case EQUIPPED -> StepOutcome.EQUIPPED;
            case INVALID_PLAYER -> {
                sequence.removeParticipant(participant, RemovalReason.INVALID_STATE);
                yield StepOutcome.SKIPPED;
            }
            case MISSING, OCCUPIED -> config.missingItemPolicy() == MissingItemPolicy.ABORT
                    ? StepOutcome.ABORTED : StepOutcome.SKIPPED;
        };
        if (result.equipped()) {
            sounds.playStep(player, step, result.item().getType(), config.sounds());
            if (config.visuals().particlesEnabled()) {
                spawnParticles(player, step);
            }
        }
        if (config.debugLogTicks()) {
            logger.info(String.format(Locale.ROOT,
                    "[debug] sequence #%d step %d/%d %-10s player=%-16s scheduled=%d actual=%d drift=%d result=%s",
                    sequence.id(), stepIndex + 1, sequence.timeline().steps().size(), step, player.getName(),
                    scheduledTick, actualTick, actualTick - scheduledTick, result.status()));
        }
        return outcome;
    }

    @Override
    public void afterStep(SyncSequence sequence, EquipmentStep step, int stepIndex, long tick) {
        SyncEquipSettings config = settingsFor(sequence);
        if (!config.visuals().actionbarEnabled()) {
            return;
        }
        int total = sequence.timeline().steps().size();
        String bar = "▰".repeat(stepIndex + 1) + "▱".repeat(total - stepIndex - 1);
        for (Player player : onlineParticipants(sequence)) {
            messages.actionBar(player, "sequence.progress-actionbar", ph("bar", bar), ph("piece", step.displayName()));
        }
    }

    @Override
    public void onParticipantRemoved(SyncSequence sequence, UUID participant, RemovalReason reason,
                                     int equippedCount) {
        Player player = server.getPlayer(participant);
        Component reasonText = messages.render("reason." + reason.name().toLowerCase(Locale.ROOT));
        if (player != null && reason != RemovalReason.DISCONNECTED) {
            if (equippedCount == 0) {
                messages.send(player, "sequence.removed-before-start", ph("reason", reasonText));
            } else {
                messages.send(player, "sequence.interrupted", ph("reason", reasonText),
                        ph("count", equippedCount), ph("total", sequence.timeline().steps().size()));
            }
        }
        if (sequence.mode() == SyncSequence.Mode.SQUAD) {
            String name = player != null ? player.getName() : server.getOfflinePlayer(participant).getName();
            for (Player other : onlineParticipants(sequence)) {
                messages.send(other, "sequence.member-dropped", ph("player", name == null ? "?" : name),
                        ph("reason", reasonText));
            }
        }
    }

    @Override
    public void onFinished(SyncSequence sequence, FinishReason reason) {
        SyncEquipSettings config = settingsFor(sequence);
        int total = sequence.timeline().steps().size();
        for (Player player : onlineParticipants(sequence)) {
            int count = sequence.equippedCount(player.getUniqueId());
            switch (reason) {
                case COMPLETED -> {
                    if (config.visuals().actionbarEnabled()) {
                        messages.actionBar(player, "sequence.completed-actionbar", ph("count", count),
                                ph("total", total));
                    }
                    if (count < total) {
                        messages.send(player, "sequence.completed-partial", ph("count", count), ph("total", total));
                    }
                    if (config.sounds().completionSoundEnabled()) {
                        sounds.playCue(player, config.sounds().completionSound(), 0.6f, 1.4f);
                    }
                }
                case CANCELLED, SHUTDOWN -> {
                    if (sequence.timeline().countdownSeconds() > 0) {
                        player.clearTitle();
                    }
                    String key = reason == FinishReason.SHUTDOWN ? "sequence.shutdown" : "sequence.cancelled";
                    if (count == 0) {
                        messages.send(player, key + "-before-start");
                    } else {
                        messages.send(player, key + "-midway", ph("count", count), ph("total", total));
                    }
                }
                case NO_PARTICIPANTS -> {
                }
            }
        }
        if (config.debugLogTicks()) {
            logger.info(String.format(Locale.ROOT, "[debug] sequence #%d finished: %s (drift=%d)",
                    sequence.id(), reason, sequence.drift()));
        }
        sequenceSettings.remove(sequence.id());
    }


    private SyncEquipSettings settingsFor(SyncSequence sequence) {
        SyncEquipSettings config = sequenceSettings.get(sequence.id());
        return config != null ? config : settings.get();
    }

    private List<Player> onlineParticipants(SyncSequence sequence) {
        List<Player> players = new ArrayList<>(sequence.participants().size());
        for (UUID id : sequence.participants()) {
            Player player = server.getPlayer(id);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    private void spawnParticles(Player player, EquipmentStep step) {
        Location at = player.getLocation().add(0, step.bodyHeight(), 0);
        player.getWorld().spawnParticle(Particle.CRIT, at, 5, 0.25, 0.08, 0.25, 0.01);
    }

    public static String describe(List<EquipmentStep> steps) {
        return steps.stream().map(EquipmentStep::displayName).collect(Collectors.joining(", "));
    }
}
