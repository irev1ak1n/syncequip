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

/**
 * Starts equip and unequip sequences and executes them step by step. Solo and squad mode share this one
 * implementation: a solo sequence is just a sequence with one participant.
 */
public final class SequenceService implements SequenceCallbacks {

    public static final String BYPASS_COOLDOWN = "syncequip.bypass.cooldown";

    public enum Action {
        EQUIP,
        UNEQUIP
    }

    /** Everything a running sequence needs to remember, fixed when it starts so a reload cannot change it. */
    private record Context(SyncEquipSettings settings, Loadout loadout, Action action, boolean acceptExisting) {

        String key(String base) {
            return (action == Action.UNEQUIP ? "unequip." : "sequence.") + base;
        }
    }

    private final Server server;
    private final Logger logger;
    private final TickSequenceScheduler scheduler;
    private final Supplier<SyncEquipSettings> settings;
    private final EquipmentManager equipmentManager;
    private final EquipmentSoundManager sounds;
    private final MessageUtil messages;
    private final CooldownTracker cooldowns;
    private final LongSupplier currentTick;
    private final Map<Long, Context> contexts = new HashMap<>();

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

    // ------------------------------------------------------------------ readiness

    /** Checks whether a player could equip the original solo kit right now. */
    public Optional<Component> checkReady(Player player, SyncEquipSettings config) {
        return checkReady(player, config, config.soloLoadout(), config.soloUseDefaultLoadout(), false);
    }

    /**
     * Checks whether a player could take part in an equip sequence for this loadout.
     *
     * @param requireWork if true, a player who has nothing left to equip is told so instead of starting a no-op
     * @return a short rendered reason if they cannot, or empty if they are ready
     */
    public Optional<Component> checkReady(Player player, SyncEquipSettings config, Loadout loadout,
                                          boolean acceptExisting, boolean requireWork) {
        Optional<Component> basic = checkBasics(player);
        if (basic.isPresent()) {
            return basic;
        }
        InventoryValidator.Report report = InventoryValidator.validate(player, loadout, config.requireEmptySlots(),
                acceptExisting);
        if (report.acceptableFor(config.missingItemPolicy())) {
            if (requireWork && report.available().isEmpty()) {
                return Optional.of(messages.render("problem.nothing"));
            }
            return Optional.empty();
        }
        if (report.available().isEmpty() && report.satisfied().isEmpty()
                && config.missingItemPolicy() == MissingItemPolicy.SKIP
                && report.missing().isEmpty() && report.occupied().isEmpty()) {
            return Optional.of(messages.render("problem.nothing"));
        }
        List<Component> parts = new ArrayList<>();
        if (!report.missing().isEmpty()) {
            parts.add(messages.render("problem.missing", ph("pieces", loadout.describe(report.missing()))));
        }
        if (!report.conflicts().isEmpty()) {
            parts.add(messages.render("problem.conflict", ph("pieces", loadout.describe(report.conflicts()))));
        }
        List<EquipmentStep> wornSlots = report.occupied().stream().filter(s -> !s.isHand()).toList();
        List<EquipmentStep> busyHands = report.occupied().stream().filter(EquipmentStep::isHand).toList();
        if (!wornSlots.isEmpty()) {
            parts.add(messages.render("problem.occupied", ph("pieces", loadout.describe(wornSlots))));
        }
        if (!busyHands.isEmpty()) {
            parts.add(messages.render("problem.occupied-hand", ph("pieces", loadout.describe(busyHands))));
        }
        if (parts.isEmpty()) {
            parts.add(messages.render("problem.nothing"));
        }
        return Optional.of(Component.join(JoinConfiguration.commas(true), parts));
    }

    private Optional<Component> checkBasics(Player player) {
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
        return Optional.empty();
    }

    /** Result of {@link #checkUnequip}: a reason the player cannot take part, or a note that there is nothing to remove. */
    public record UnequipCheck(Component problem, boolean nothingToRemove) {

        public boolean ready() {
            return problem == null && !nothingToRemove;
        }
    }

    /** Checks whether a player can take the loadout off, without changing anything. */
    public UnequipCheck checkUnequip(Player player, Loadout loadout) {
        Optional<Component> basic = checkBasics(player);
        if (basic.isPresent()) {
            return new UnequipCheck(basic.get(), false);
        }
        InventoryValidator.UnequipReport report = InventoryValidator.unequipReport(player, loadout);
        if (!report.enoughRoom()) {
            return new UnequipCheck(messages.render("problem.no-room"), false);
        }
        return new UnequipCheck(null, report.removable().isEmpty());
    }

    // ------------------------------------------------------------------ starting

    public boolean startSolo(Player player) {
        SyncEquipSettings config = settings.get();
        Optional<Component> problem = checkReady(player, config, config.soloLoadout(), config.soloUseDefaultLoadout(), true);
        if (problem.isPresent()) {
            messages.send(player, "solo.cannot-start", ph("reason", problem.get()));
            return false;
        }
        startGroup(SyncSequence.Mode.SOLO, null, player, List.of(player), config.soloCountdownSeconds(), config,
                config.soloLoadout(), Action.EQUIP, config.intervalTicks(), config.soloUseDefaultLoadout());
        return true;
    }

    /** Takes off the solo loadout one piece at a time, in reverse order. */
    public boolean startSoloUnequip(Player player) {
        SyncEquipSettings config = settings.get();
        UnequipCheck check = checkUnequip(player, config.soloLoadout());
        if (!check.ready()) {
            Component reason = check.problem() != null ? check.problem() : messages.render("problem.nothing-to-remove");
            messages.send(player, "unequip.cannot-start", ph("reason", reason));
            return false;
        }
        startGroup(SyncSequence.Mode.SOLO, null, player, List.of(player), config.soloCountdownSeconds(), config,
                config.soloLoadout(), Action.UNEQUIP, config.intervalTicks(), true);
        return true;
    }

    /**
     * Starts a sequence for already-validated players on one shared timeline. All participants get the same start
     * tick, so each step lands on the same server tick for every one of them.
     */
    public SyncSequence startGroup(SyncSequence.Mode mode, UUID squadId, Player initiator, List<Player> players,
                                   int countdownSeconds, SyncEquipSettings config, Loadout loadout, Action action,
                                   int intervalTicks, boolean acceptExisting) {
        List<EquipmentStep> steps = action == Action.EQUIP ? loadout.steps() : loadout.unequipSteps();
        SequenceTimeline timeline = new SequenceTimeline(countdownSeconds, intervalTicks, steps);
        // Every participant shares this one start tick, which is why each step lands on the same server tick for all of them.
        long startTick = currentTick.getAsLong() + 1;
        List<UUID> ids = players.stream().map(Player::getUniqueId).toList();
        SyncSequence sequence = scheduler.schedule(mode, squadId, initiator.getUniqueId(), ids, timeline,
                startTick, this);
        contexts.put(sequence.id(), new Context(config, loadout, action, acceptExisting));
        for (Player player : players) {
            cooldowns.start(player.getUniqueId(), config.controls().cooldownSeconds());
        }
        if (config.debugLogTicks()) {
            logger.info(String.format(Locale.ROOT,
                    "[debug] sequence #%d (%s %s) scheduled: participants=%d startTick=%d firstStepTick=%d interval=%d",
                    sequence.id(), mode, action, ids.size(), startTick, startTick + timeline.firstStepOffset(),
                    timeline.intervalTicks()));
        }
        return sequence;
    }

    public boolean isLocked(UUID player) {
        return scheduler.findByParticipant(player)
                .filter(s -> s.state() == SyncSequence.State.RUNNING)
                .map(s -> contextFor(s).settings().lockInventoryDuringSequence())
                .orElse(false);
    }

    public TickSequenceScheduler scheduler() {
        return scheduler;
    }

    /** What a running sequence is doing, or empty if the sequence is unknown. */
    public Optional<Action> actionOf(SyncSequence sequence) {
        Context ctx = contexts.get(sequence.id());
        return ctx == null ? Optional.empty() : Optional.of(ctx.action());
    }

    // ------------------------------------------------------------------ callbacks

    @Override
    public void onCountdown(SyncSequence sequence, int secondsRemaining, long tick) {
        SyncEquipSettings config = contextFor(sequence).settings();
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
        Context ctx = contextFor(sequence);
        SyncEquipSettings config = ctx.settings();
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
            if (ctx.action() == Action.EQUIP) {
                InventoryValidator.Report report = InventoryValidator.validate(player, ctx.loadout(),
                        config.requireEmptySlots(), ctx.acceptExisting());
                if (!report.acceptableFor(config.missingItemPolicy())) {
                    sequence.removeParticipant(id, RemovalReason.ITEM_UNAVAILABLE);
                }
            } else if (!InventoryValidator.unequipReport(player, ctx.loadout()).enoughRoom()) {
                sequence.removeParticipant(id, RemovalReason.NO_INVENTORY_SPACE);
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
        Context ctx = contextFor(sequence);
        SyncEquipSettings config = ctx.settings();
        Player player = server.getPlayer(participant);
        if (player == null) {
            sequence.removeParticipant(participant, RemovalReason.DISCONNECTED);
            return StepOutcome.SKIPPED;
        }
        SlotRequirement requirement = ctx.loadout().requirement(step);
        StepOutcome outcome;
        String result;
        if (ctx.action() == Action.EQUIP) {
            // Equipping is silent when custom sounds are on, so each step plays exactly one sound and never two.
            EquipmentManager.Result moved = equipmentManager.equip(player, step, requirement, ctx.loadout(),
                    config.requireEmptySlots(), config.sounds().enabled(), ctx.acceptExisting());
            result = moved.status().name();
            outcome = switch (moved.status()) {
                case EQUIPPED, ALREADY -> StepOutcome.EQUIPPED;
                case INVALID_PLAYER -> {
                    sequence.removeParticipant(participant, RemovalReason.INVALID_STATE);
                    yield StepOutcome.SKIPPED;
                }
                case MISSING, OCCUPIED, CONFLICT -> !requirement.optional() && config.missingItemPolicy() == MissingItemPolicy.ABORT
                        ? StepOutcome.ABORTED : StepOutcome.SKIPPED;
            };
            if (moved.equipped()) {
                sounds.playStep(player, step, moved.item().getType(), config.sounds());
                if (config.visuals().particlesEnabled()) {
                    spawnParticles(player, step);
                }
            }
        } else {
            EquipmentManager.UnequipResult removed = equipmentManager.unequip(player, step, requirement,
                    config.sounds().enabled());
            result = removed.status().name();
            outcome = switch (removed.status()) {
                case REMOVED -> StepOutcome.EQUIPPED;
                case NOTHING -> StepOutcome.SKIPPED;
                case NO_ROOM -> {
                    sequence.removeParticipant(participant, RemovalReason.NO_INVENTORY_SPACE);
                    yield StepOutcome.SKIPPED;
                }
                case INVALID_PLAYER -> {
                    sequence.removeParticipant(participant, RemovalReason.INVALID_STATE);
                    yield StepOutcome.SKIPPED;
                }
            };
            if (removed.removed()) {
                sounds.playStep(player.getLocation(), step, removed.item().getType(), config.sounds(),
                        config.sounds().unequipPitchMultiplier());
            }
        }
        if (config.debugLogTicks()) {
            logger.info(String.format(Locale.ROOT,
                    "[debug] sequence #%d step %d/%d %-10s player=%-16s scheduled=%d actual=%d drift=%d result=%s",
                    sequence.id(), stepIndex + 1, sequence.timeline().steps().size(), step, player.getName(),
                    scheduledTick, actualTick, actualTick - scheduledTick, result));
        }
        return outcome;
    }

    @Override
    public void afterStep(SyncSequence sequence, EquipmentStep step, int stepIndex, long tick) {
        Context ctx = contextFor(sequence);
        if (!ctx.settings().visuals().actionbarEnabled()) {
            return;
        }
        int total = sequence.timeline().steps().size();
        String bar = "▰".repeat(stepIndex + 1) + "▱".repeat(total - stepIndex - 1);
        String piece = ctx.loadout().label(step);
        for (Player player : onlineParticipants(sequence)) {
            messages.actionBar(player, "sequence.progress-actionbar", ph("bar", bar), ph("piece", piece));
        }
    }

    @Override
    public void onParticipantRemoved(SyncSequence sequence, UUID participant, RemovalReason reason,
                                     int equippedCount) {
        Context ctx = contextFor(sequence);
        Player player = server.getPlayer(participant);
        Component reasonText = messages.render("reason." + reason.name().toLowerCase(Locale.ROOT));
        if (player != null && reason != RemovalReason.DISCONNECTED) {
            if (equippedCount == 0) {
                messages.send(player, ctx.key("removed-before-start"), ph("reason", reasonText));
            } else {
                messages.send(player, ctx.key("interrupted"), ph("reason", reasonText),
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
        Context ctx = contextFor(sequence);
        SyncEquipSettings config = ctx.settings();
        int total = sequence.timeline().steps().size();
        for (Player player : onlineParticipants(sequence)) {
            int count = sequence.equippedCount(player.getUniqueId());
            switch (reason) {
                case COMPLETED -> {
                    if (config.visuals().actionbarEnabled()) {
                        messages.actionBar(player, ctx.key("completed-actionbar"), ph("count", count),
                                ph("total", total));
                    }
                    if (count < total) {
                        messages.send(player, ctx.key("completed-partial"), ph("count", count), ph("total", total));
                    }
                    if (config.sounds().completionSoundEnabled()) {
                        sounds.playCue(player, config.sounds().completionSound(), 0.6f, 1.4f);
                    }
                }
                case CANCELLED, SHUTDOWN -> {
                    if (sequence.timeline().countdownSeconds() > 0) {
                        player.clearTitle();
                    }
                    String base = reason == FinishReason.SHUTDOWN ? "shutdown" : "cancelled";
                    if (count == 0) {
                        messages.send(player, ctx.key(base + "-before-start"));
                    } else {
                        messages.send(player, ctx.key(base + "-midway"), ph("count", count), ph("total", total));
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
        contexts.remove(sequence.id());
    }

    // ------------------------------------------------------------------ helpers

    private Context contextFor(SyncSequence sequence) {
        Context ctx = contexts.get(sequence.id());
        if (ctx != null) {
            return ctx;
        }
        SyncEquipSettings config = settings.get();
        return new Context(config, config.soloLoadout(), Action.EQUIP, false);
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
