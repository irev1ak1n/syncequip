package dev.syncequip.demo;

import dev.syncequip.SyncEquipPlugin;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

/**
 * A scripted walkthrough of the main features. Test players (named with a prefix, normally Mineflayer bots) are lined
 * up in front of the viewer and driven with the ordinary SyncEquip commands, so every equipment change is a real
 * server-side change made by the normal plugin code.
 *
 * <p>The tour only plays the viewer's titles and waits. The bots are told when to walk by a private message that starts
 * with {@link #CUE}. Everything it changes is put back by {@link #cleanup}.
 */
public final class TourManager {

    public static final String CUE = "[SE-TOUR] ";
    public static final String SQUAD = "NULL";
    private static final int MAX_BOTS = 10;
    private static final int MIN_BOTS = 2;
    private static final int LINE_DISTANCE = 10;
    private static final int STRIP_START = 8;
    private static final int STRIP_DEPTH = 3;
    private static final int WALK_MILLIS = 900;
    private static final int JOIN_TIMEOUT = 20 * 8;
    private static final int SEQUENCE_TIMEOUT = 20 * 40;

    public enum StartResult {
        STARTED,
        NO_BOTS,
        RUNNING
    }

    private record Step(String title, String subtitle, Runnable action, int hold, BooleanSupplier until, int timeout) {
    }

    private final SyncEquipPlugin plugin;
    private final Map<Location, BlockData> changedBlocks = new LinkedHashMap<>();
    private final List<Step> steps = new ArrayList<>();
    private List<Player> bots = List.of();
    private Player viewer;
    private BukkitTask task;
    private boolean paused;
    private int index;
    private int delay;
    private int waited;
    private Step waiting;
    private int stage;

    public TourManager(SyncEquipPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean running() {
        return task != null;
    }

    public boolean paused() {
        return running() && paused;
    }

    public String prefix() {
        return plugin.getConfig().getString("demo.tour.bot-prefix", "TourBot");
    }

    public List<Player> findBots(Player except) {
        String prefix = prefix().toLowerCase(Locale.ROOT);
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p != except && p.getName().toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(Comparator.comparing(Player::getName))
                .limit(MAX_BOTS)
                .map(p -> (Player) p)
                .toList();
    }

    // ------------------------------------------------------------------ controls

    public StartResult start(Player viewer) {
        if (running()) {
            return StartResult.RUNNING;
        }
        List<Player> found = findBots(viewer);
        if (found.size() < MIN_BOTS) {
            return StartResult.NO_BOTS;
        }
        this.viewer = viewer;
        this.bots = found;
        paused = false;
        index = 0;
        delay = 0;
        waited = 0;
        waiting = null;
        stage = 0;
        changedBlocks.clear();
        steps.clear();
        buildSteps();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return StartResult.STARTED;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    /** Stops the tour and puts everything back. Returns false if no tour was running. */
    public boolean stop() {
        if (!running()) {
            return false;
        }
        finish(Component.text("Tour stopped", NamedTextColor.YELLOW), Component.empty());
        return true;
    }

    public StartResult restart(Player newViewer) {
        stop();
        return start(newViewer);
    }

    /** Called when the plugin shuts down, so no obsidian is left behind. */
    public void shutdown() {
        if (running()) {
            task.cancel();
            task = null;
            cleanup();
        }
    }

    // ------------------------------------------------------------------ the loop

    private void tick() {
        if (paused || viewer == null || !viewer.isOnline()) {
            if (viewer == null || !viewer.isOnline()) {
                finish(Component.empty(), Component.empty());
            }
            return;
        }
        if (waiting != null) {
            waited++;
            if (waiting.until().getAsBoolean() || waited >= waiting.timeout()) {
                delay = waiting.hold();
                waiting = null;
            }
            return;
        }
        if (delay > 0) {
            delay--;
            return;
        }
        if (index >= steps.size()) {
            finish(Component.text("Tour complete", NamedTextColor.GREEN),
                    Component.text("Run /se demo tour again to replay", NamedTextColor.GRAY));
            return;
        }
        Step step = steps.get(index++);
        if (!step.title().isEmpty()) {
            show(step.title(), step.subtitle());
        }
        step.action().run();
        if (step.until() != null) {
            waiting = step;
            waited = 0;
        } else {
            delay = step.hold();
        }
    }

    private void show(String title, String subtitle) {
        viewer.showTitle(Title.title(Component.text(title, NamedTextColor.GOLD),
                Component.text(subtitle, NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(500))));
        viewer.sendMessage(Component.text("[Tour] " + title + " - " + subtitle, NamedTextColor.GOLD));
    }

    private void finish(Component title, Component subtitle) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        cleanup();
        if (viewer != null && viewer.isOnline() && !title.equals(Component.empty())) {
            viewer.showTitle(Title.title(title, subtitle));
        }
    }

    // ------------------------------------------------------------------ the script

    private void add(String title, String subtitle, Runnable action, int hold) {
        steps.add(new Step(title, subtitle, action, hold, null, 0));
    }

    private void addAndWait(String title, String subtitle, Runnable action, BooleanSupplier until, int hold, int timeout) {
        steps.add(new Step(title, subtitle, action, hold, until, timeout));
    }

    private void run(String command) {
        plugin.getServer().dispatchCommand(leader(), "syncequip " + command);
    }

    private Player leader() {
        return bots.getFirst();
    }

    private boolean idle() {
        return bots.stream().noneMatch(b -> plugin.sequenceService().scheduler().isBusy(b.getUniqueId()));
    }

    private void equipAndWait(String title, String subtitle, int hold) {
        addAndWait(title, subtitle, () -> run("squad start"), this::idle, hold, SEQUENCE_TIMEOUT);
    }

    private void unequipAndWait(String title, String subtitle, int hold) {
        addAndWait(title, subtitle, () -> run("squad unequip"), this::idle, hold, SEQUENCE_TIMEOUT);
    }

    private void buildSteps() {
        int total = 8;
        add(stageTitle(1, total, "Player formation"), bots.size() + " test players line up in front of you",
                this::formUp, 80);

        add(stageTitle(2, total, "Joining by block"), "Walking onto obsidian puts them in squad " + SQUAD,
                this::prepareJoining, 30);
        addAndWait("", "", this::walkOntoObsidian, this::everyoneJoined, 0, JOIN_TIMEOUT);
        add("", "", this::joinStragglers, 30);

        equipAndWait(stageTitle(3, total, "Synchronized equipment"), "Netherite armor, sword and shield on the same ticks", 60);

        for (int speed : new int[] {1, 2, 4, 6}) {
            unequipAndWait(stageTitle(4, total, "Speed comparison"), "Taking it off, then " + speed + " tick(s) per piece", 20);
            add("", "", () -> run("squad speed " + speed), 5);
            equipAndWait(stageTitle(4, total, "Speed " + speed), speed + " tick(s) between pieces", 40);
        }
        add("", "", () -> run("squad speed default"), 5);

        unequipAndWait(stageTitle(5, total, "Custom loadout"), "Totem in the offhand, an axe, and no helmet", 20);
        add("", "", this::customLoadout, 10);
        equipAndWait("", "", 60);

        unequipAndWait(stageTitle(6, total, "Synchronized unequipping"), "Everyone takes their gear off together", 40);
        add("", "", () -> run("squad loadout reset"), 10);

        add(stageTitle(7, total, "Switching items"), "Holding an axe, a pickaxe, a totem or old boots",
                this::awkwardInventories, 70);
        equipAndWait("", "The required sword, shield and armor are swapped in", 60);

        unequipAndWait(stageTitle(8, total, "Final demonstration"), "One last full equip, perfectly in step", 20);
        add("", "", () -> {
            run("squad loadout reset");
            giveKits();
        }, 10);
        equipAndWait("", "", 100);
    }

    private static String stageTitle(int number, int total, String name) {
        return number + "/" + total + "  " + name;
    }

    // ------------------------------------------------------------------ actions

    private void formUp() {
        cleanupSquad();
        giveKits();
        World world = viewer.getWorld();
        int[] facing = cardinal(viewer.getLocation().getYaw());
        int fx = facing[0];
        int fz = facing[1];
        Block origin = viewer.getLocation().getBlock();
        int count = bots.size();
        float yaw = (float) Math.toDegrees(Math.atan2(fx, -fz));
        for (int i = 0; i < count; i++) {
            int offset = 2 * i - (count - 1);
            int x = origin.getX() + fx * LINE_DISTANCE - fz * offset;
            int z = origin.getZ() + fz * LINE_DISTANCE + fx * offset;
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location at = new Location(world, x + 0.5, y, z + 0.5, yaw, 0);
            bots.get(i).teleport(at);
        }
    }

    /** The compass direction a yaw points to, as a step in x and z. */
    private static int[] cardinal(float yaw) {
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians);
        double dz = Math.cos(radians);
        return Math.abs(dx) > Math.abs(dz) ? new int[] {dx > 0 ? 1 : -1, 0} : new int[] {0, dz > 0 ? 1 : -1};
    }

    private void prepareJoining() {
        World world = viewer.getWorld();
        int[] facing = cardinal(viewer.getLocation().getYaw());
        int fx = facing[0];
        int fz = facing[1];
        Block origin = viewer.getLocation().getBlock();
        int count = bots.size();
        for (int i = 0; i < count; i++) {
            int offset = 2 * i - (count - 1);
            for (int depth = 0; depth < STRIP_DEPTH; depth++) {
                int distance = STRIP_START - depth;
                int x = origin.getX() + fx * distance - fz * offset;
                int z = origin.getZ() + fz * distance + fx * offset;
                Block ground = world.getBlockAt(x, world.getHighestBlockYAt(x, z), z);
                changedBlocks.putIfAbsent(ground.getLocation(), ground.getBlockData());
                ground.setType(Material.OBSIDIAN, false);
            }
        }
        run("squad create " + SQUAD);
        run("squad block set obsidian");
    }

    private void walkOntoObsidian() {
        for (Player bot : bots) {
            if (bot != leader()) {
                bot.sendMessage(Component.text(CUE + "walk " + WALK_MILLIS));
            }
        }
    }

    private boolean everyoneJoined() {
        return bots.stream().allMatch(b -> plugin.squads().squadOf(b.getUniqueId()).isPresent());
    }

    /** A bot that never walked onto the obsidian (for example one that is not a real client) is let in the same way. */
    private void joinStragglers() {
        plugin.squads().profile(SQUAD).ifPresent(profile -> {
            for (Player bot : bots) {
                if (plugin.squads().squadOf(bot.getUniqueId()).isEmpty()) {
                    plugin.getLogger().warning("Tour: " + bot.getName() + " did not walk onto the obsidian, joining directly.");
                    plugin.joinBlocks().onStep(bot, profile);
                }
            }
        });
    }

    private void customLoadout() {
        for (Player bot : bots) {
            PlayerInventory inv = bot.getInventory();
            inv.setItem(14, new ItemStack(Material.NETHERITE_AXE));
            inv.setItem(15, new ItemStack(Material.TOTEM_OF_UNDYING));
        }
        run("squad loadout set offhand totem_of_undying");
        run("squad loadout set mainhand any_axe");
        run("squad loadout set helmet none");
    }

    /** Every fourth bot gets a different problem for the planner to solve. */
    private void awkwardInventories() {
        run("squad loadout set boots netherite");
        for (int i = 0; i < bots.size(); i++) {
            Player bot = bots.get(i);
            giveKit(bot);
            PlayerInventory inv = bot.getInventory();
            switch (i % 4) {
                case 0 -> {
                    inv.setItem(0, new ItemStack(Material.NETHERITE_AXE));
                    inv.setItem(4, new ItemStack(Material.NETHERITE_SWORD));
                }
                case 1 -> {
                    inv.setItem(20, new ItemStack(Material.NETHERITE_SWORD));
                    inv.setItem(0, new ItemStack(Material.NETHERITE_PICKAXE));
                }
                case 2 -> inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
                default -> inv.setBoots(new ItemStack(Material.LEATHER_BOOTS));
            }
            inv.setHeldItemSlot(0);
        }
    }

    // ------------------------------------------------------------------ items and cleanup

    private void giveKits() {
        bots.forEach(this::giveKit);
    }

    private void giveKit(Player bot) {
        PlayerInventory inv = bot.getInventory();
        inv.clear();
        inv.setHeldItemSlot(0);
        inv.setItem(0, new ItemStack(Material.NETHERITE_SWORD));
        inv.setItem(9, new ItemStack(Material.NETHERITE_BOOTS));
        inv.setItem(10, new ItemStack(Material.NETHERITE_LEGGINGS));
        inv.setItem(11, new ItemStack(Material.NETHERITE_CHESTPLATE));
        inv.setItem(12, new ItemStack(Material.NETHERITE_HELMET));
        inv.setItem(13, new ItemStack(Material.SHIELD));
    }

    private void cleanupSquad() {
        plugin.squads().disband(SQUAD);
    }

    private void cleanup() {
        for (Player bot : bots) {
            plugin.sequenceService().scheduler().findByParticipant(bot.getUniqueId())
                    .ifPresent(s -> s.cancel(dev.syncequip.synchronization.FinishReason.CANCELLED));
        }
        cleanupSquad();
        for (Player bot : bots) {
            if (bot.isOnline()) {
                bot.getInventory().clear();
                bot.getInventory().setItemInOffHand(null);
                bot.getInventory().setHeldItemSlot(0);
            }
        }
        changedBlocks.forEach((at, data) -> at.getBlock().setBlockData(data, false));
        changedBlocks.clear();
        bots = List.of();
    }
}
