package dev.syncequip.demo;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.audio.EquipmentSoundManager;
import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.equipment.EquipmentStep;
import dev.syncequip.synchronization.FinishReason;
import dev.syncequip.synchronization.RemovalReason;
import dev.syncequip.synchronization.SequenceCallbacks;
import dev.syncequip.synchronization.SequenceTimeline;
import dev.syncequip.synchronization.StepOutcome;
import dev.syncequip.synchronization.SyncSequence;
import dev.syncequip.synchronization.TickSequenceScheduler;
import dev.syncequip.util.MessageUtil;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Demo mode: a row of stationary husks that are equipped on the shared tick scheduler, so the
 * sequence can be watched without other players. The husks wear temporary netherite gear that
 * never touches a player inventory and never drops.
 *
 * <p>Timing comes from the same {@link TickSequenceScheduler} as solo and squad sequences. Only the
 * pause between repeated runs uses an ordinary delayed task.
 */
public final class DemoManager implements SequenceCallbacks {

    public static final int MAX_REPEATS = 100;

    private static final Map<EquipmentStep, Material> GEAR = new EnumMap<>(EquipmentStep.class);

    static {
        GEAR.put(EquipmentStep.BOOTS, Material.NETHERITE_BOOTS);
        GEAR.put(EquipmentStep.LEGGINGS, Material.NETHERITE_LEGGINGS);
        GEAR.put(EquipmentStep.CHESTPLATE, Material.NETHERITE_CHESTPLATE);
        GEAR.put(EquipmentStep.HELMET, Material.NETHERITE_HELMET);
        GEAR.put(EquipmentStep.SHIELD, Material.SHIELD);
    }

    /** Creates one husk. Replaceable so tests can run without a real server. */
    @FunctionalInterface
    public interface MobSpawner {
        Husk spawn(Location at, Consumer<Husk> init);
    }

    public enum StartResult {
        STARTED,
        NO_MOBS,
        BUSY
    }

    private final JavaPlugin plugin;
    private final Server server;
    private final Logger logger;
    private final TickSequenceScheduler scheduler;
    private final Supplier<SyncEquipSettings> settings;
    private final EquipmentSoundManager sounds;
    private final MessageUtil messages;
    private final LongSupplier currentTick;
    private final NamespacedKey tagKey;
    private MobSpawner spawner = DemoManager::spawnWithPaper;

    private final List<UUID> mobs = new ArrayList<>();
    private final Map<UUID, Integer> labels = new HashMap<>();
    private Integer intervalOverride;
    private UUID owner;
    private SyncSequence active;
    private SyncEquipSettings runSettings;
    private int runsTotal;
    private int runsDone;
    private BukkitTask holdTask;

    public DemoManager(JavaPlugin plugin, TickSequenceScheduler scheduler, Supplier<SyncEquipSettings> settings,
                       EquipmentSoundManager sounds, MessageUtil messages, LongSupplier currentTick) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.logger = plugin.getLogger();
        this.scheduler = scheduler;
        this.settings = settings;
        this.sounds = sounds;
        this.messages = messages;
        this.currentTick = currentTick;
        this.tagKey = new NamespacedKey(plugin, "demo");
    }

    // ------------------------------------------------------------------ mobs

    public boolean isDemoMob(Entity entity) {
        return entity.getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    /** Spawns a row of {@code count} husks in front of the player, replacing any earlier row. */
    public int spawn(Player player, int count) {
        clear();
        SyncEquipSettings.Demo demo = settings.get().demo();
        Location base = player.getLocation();
        Vector forward = base.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-6) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        Location center = base.clone().add(forward.clone().multiply(demo.distance()));
        float yaw = demo.facing() == SyncEquipSettings.DemoFacing.TOWARD_PLAYER ? base.getYaw() + 180f : base.getYaw();

        for (int i = 0; i < count; i++) {
            double offset = (i - (count - 1) / 2.0) * demo.spacing();
            Location at = center.clone().add(side.clone().multiply(offset));
            at.setY(groundY(at, base.getY()));
            at.setYaw(yaw);
            at.setPitch(0f);
            Husk husk = spawner.spawn(at, this::configure);
            if (husk == null || !husk.isValid()) {
                continue;
            }
            husk.getPassengers().forEach(Entity::remove);
            // Spawning can hand out random gear, so make sure every slot really starts empty.
            clearEquipment(husk);
            mobs.add(husk.getUniqueId());
            labels.put(husk.getUniqueId(), mobs.size());
        }
        return mobs.size();
    }

    public void setSpawner(MobSpawner spawner) {
        this.spawner = spawner;
    }

    /** Spawns without randomised data, so no random gear, baby variant or chicken rider is applied. */
    private static Husk spawnWithPaper(Location at, Consumer<Husk> init) {
        return at.getWorld().spawn(at, Husk.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false, init);
    }

    @SuppressWarnings("deprecation")
    private void configure(Husk husk) {
        husk.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        husk.setAI(false);
        husk.setAware(false);
        husk.setTarget(null);
        husk.setSilent(true);
        husk.setInvulnerable(true);
        husk.setCollidable(false);
        husk.setPersistent(false);
        husk.setRemoveWhenFarAway(false);
        husk.setCanPickupItems(false);
        husk.setBaby(false);
        husk.setShouldBurnInDay(false);
        husk.setCanBreakDoors(false);
        EntityEquipment equipment = husk.getEquipment();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            equipment.setDropChance(slot, 0f);
        }
    }

    private static double groundY(Location at, double fallback) {
        try {
            RayTraceResult hit = at.getWorld().rayTraceBlocks(at.clone().add(0, 2, 0), new Vector(0, -1, 0), 8,
                    FluidCollisionMode.NEVER, true);
            return hit != null ? hit.getHitPosition().getY() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void clearEquipment(Husk husk) {
        EntityEquipment equipment = husk.getEquipment();
        for (EquipmentStep step : EquipmentStep.values()) {
            equipment.setItem(step.slot(), null, true);
        }
        equipment.setItemInMainHand(null, true);
    }

    /** Live demo husks, in spawn order. Mobs that no longer exist are dropped from the list. */
    public List<Husk> liveMobs() {
        List<Husk> live = new ArrayList<>();
        mobs.removeIf(id -> {
            Entity entity = server.getEntity(id);
            if (entity instanceof Husk husk && husk.isValid()) {
                live.add(husk);
                return false;
            }
            labels.remove(id);
            return true;
        });
        return live;
    }

    public boolean isTracked(UUID id) {
        return mobs.contains(id);
    }

    public int mobCount() {
        return liveMobs().size();
    }

    // ------------------------------------------------------------------ running

    public int interval() {
        return intervalOverride != null ? intervalOverride : settings.get().demo().intervalTicks();
    }

    /** Sets the interval used by later runs. Does not change a run that is already going. */
    public void setInterval(int ticks) {
        if (ticks < SequenceTimeline.MIN_INTERVAL || ticks > SequenceTimeline.MAX_INTERVAL) {
            throw new IllegalArgumentException("interval must be between 1 and 10");
        }
        intervalOverride = ticks;
    }

    public boolean isRunning() {
        return active != null || holdTask != null;
    }

    public StartResult start(Player requester, int runs) {
        if (isRunning()) {
            return StartResult.BUSY;
        }
        if (liveMobs().isEmpty()) {
            return StartResult.NO_MOBS;
        }
        owner = requester.getUniqueId();
        runsTotal = runs;
        runsDone = 0;
        launch();
        return StartResult.STARTED;
    }

    private void launch() {
        List<Husk> live = liveMobs();
        if (live.isEmpty()) {
            runsTotal = 0;
            return;
        }
        resetEquipment(live);
        runSettings = settings.get();
        SequenceTimeline timeline = new SequenceTimeline(runSettings.demo().countdownSeconds(), interval(),
                runSettings.order());
        List<UUID> ids = live.stream().map(Entity::getUniqueId).toList();
        long startTick = currentTick.getAsLong() + 1;
        try {
            active = scheduler.schedule(SyncSequence.Mode.DEMO, null, owner, ids, timeline, startTick, this);
        } catch (IllegalStateException e) {
            runsTotal = 0;
            logger.warning("Demo could not start: " + e.getMessage());
            return;
        }
        if (runSettings.debugLogTicks()) {
            logger.info(String.format(Locale.ROOT,
                    "[debug] sequence #%d (DEMO) scheduled: participants=%d startTick=%d firstStepTick=%d interval=%d",
                    active.id(), ids.size(), startTick, startTick + timeline.firstStepOffset(),
                    timeline.intervalTicks()));
        }
    }

    /** Stops a running demo. Pieces already on the mobs stay on. */
    public boolean stop() {
        boolean was = isRunning();
        runsTotal = 0;
        if (holdTask != null) {
            holdTask.cancel();
            holdTask = null;
        }
        if (active != null) {
            active.cancel(FinishReason.CANCELLED);
        }
        active = null;
        return was;
    }

    /** Removes all equipment from the demo mobs so the sequence can be replayed. */
    public int reset() {
        stop();
        List<Husk> live = liveMobs();
        resetEquipment(live);
        return live.size();
    }

    private void resetEquipment(List<Husk> live) {
        for (Husk husk : live) {
            clearEquipment(husk);
        }
    }

    /** Removes every demo mob, including leftovers from earlier sessions. Returns how many went. */
    public int clear() {
        stop();
        Set<UUID> removed = new HashSet<>();
        for (UUID id : mobs) {
            Entity entity = server.getEntity(id);
            if (entity != null) {
                entity.remove();
                removed.add(id);
            }
        }
        mobs.clear();
        labels.clear();
        removed.addAll(removeTaggedMobs());
        return removed.size();
    }

    /** Removes tagged husks that this manager does not track, e.g. after a reload or crash. */
    public Set<UUID> removeTaggedMobs() {
        Set<UUID> removed = new HashSet<>();
        for (World world : server.getWorlds()) {
            for (Husk husk : world.getEntitiesByClass(Husk.class)) {
                if (isDemoMob(husk) && !mobs.contains(husk.getUniqueId())) {
                    husk.remove();
                    removed.add(husk.getUniqueId());
                }
            }
        }
        return removed;
    }

    public void shutdown() {
        clear();
    }

    // ------------------------------------------------------------------ callbacks

    private Player ownerPlayer() {
        return owner == null ? null : server.getPlayer(owner);
    }

    @Override
    public void onCountdown(SyncSequence sequence, int secondsRemaining, long tick) {
        Player player = ownerPlayer();
        if (player == null) {
            return;
        }
        switch (runSettings.visuals().countdownDisplay()) {
            case TITLE -> messages.title(player, "sequence.countdown-title", "sequence.countdown-subtitle",
                    ph("seconds", secondsRemaining));
            case ACTIONBAR -> messages.actionBar(player, "sequence.countdown-actionbar",
                    ph("seconds", secondsRemaining));
            case NONE -> {
            }
        }
        if (runSettings.sounds().countdownSoundEnabled()) {
            sounds.playCue(player, runSettings.sounds().countdownSound(), 0.6f, 1.0f);
        }
    }

    @Override
    public void onBeforeFirstStep(SyncSequence sequence, long tick) {
        for (UUID id : List.copyOf(sequence.participants())) {
            Entity entity = server.getEntity(id);
            if (!(entity instanceof Husk husk) || !husk.isValid()) {
                sequence.removeParticipant(id, RemovalReason.INVALID_STATE);
            }
        }
        Player player = ownerPlayer();
        if (player != null && runSettings.visuals().countdownDisplay() == SyncEquipSettings.CountdownDisplay.TITLE
                && sequence.timeline().countdownSeconds() > 0) {
            player.clearTitle();
        }
    }

    @Override
    public StepOutcome executeStep(SyncSequence sequence, UUID participant, EquipmentStep step, int stepIndex,
                                   long scheduledTick, long actualTick) {
        Entity entity = server.getEntity(participant);
        if (!(entity instanceof Husk husk) || !husk.isValid()) {
            return StepOutcome.ABORTED;
        }
        Material material = GEAR.get(step);
        husk.getEquipment().setItem(step.slot(), new ItemStack(material), runSettings.sounds().enabled());
        sounds.playStep(husk.getLocation(), step, material, runSettings.sounds());
        if (runSettings.debugLogTicks()) {
            logger.info(String.format(Locale.ROOT,
                    "[debug] sequence #%d step %d/%d %-10s player=%-16s scheduled=%d actual=%d drift=%d result=EQUIPPED",
                    sequence.id(), stepIndex + 1, sequence.timeline().steps().size(), step,
                    "Demo" + labels.getOrDefault(participant, 0), scheduledTick, actualTick, actualTick - scheduledTick));
        }
        return StepOutcome.EQUIPPED;
    }

    @Override
    public void onFinished(SyncSequence sequence, FinishReason reason) {
        if (sequence != active) {
            return;
        }
        active = null;
        Player player = ownerPlayer();
        if (runSettings.debugLogTicks()) {
            logger.info(String.format(Locale.ROOT, "[debug] sequence #%d finished: %s (drift=%d)", sequence.id(),
                    reason, sequence.drift()));
        }
        if (reason != FinishReason.COMPLETED) {
            runsTotal = 0;
            if (player != null && reason == FinishReason.CANCELLED) {
                messages.send(player, "demo.stopped");
            }
            return;
        }
        runsDone++;
        if (player != null) {
            messages.actionBar(player, "demo.run-finished", ph("run", runsDone), ph("total", runsTotal));
        }
        if (runsDone < runsTotal) {
            long hold = (long) runSettings.demo().repeatHoldSeconds() * SequenceTimeline.TICKS_PER_SECOND;
            holdTask = server.getScheduler().runTaskLater(plugin, () -> {
                holdTask = null;
                launch();
            }, Math.max(1L, hold));
        } else if (player != null) {
            messages.send(player, "demo.finished", ph("count", sequence.participants().size()));
        }
    }
}
