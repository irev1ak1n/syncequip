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
    private static final int DEFAULT_BOTS = 5;
    private static final int LINE_DISTANCE = 12;
    private static final int STRIP_START = 8;
    private static final int STRIP_DEPTH = 3;
    /** Title fade in, stay and fade out, in milliseconds. */
    private static final int FADE_IN = 250;
    private static final int STAY = 2500;
    private static final int FADE_OUT = 500;
    /** The title is fully gone after this many ticks, then comes a short pause before the demonstration starts. */
    private static final int INTRO_TICKS = (FADE_IN + STAY + FADE_OUT) / 50 + 15;
    private static final int JOIN_TIMEOUT = 20 * 8;
    private static final int MISSING_TIMEOUT = 20 * 30;
    private static final double IN_PLACE = 1.5;
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
    private Step introduced;
    private List<String> names = List.of();
    private List<Location> expected;
    private List<Location> lanes = List.of();
    private boolean needSquad;
    private int missingFor;
    private int placeTries;

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

    /** How many test players the tour uses. */
    public int required() {
        return Math.max(2, Math.min(10, plugin.getConfig().getInt("demo.tour.bots", DEFAULT_BOTS)));
    }

    public List<Player> findBots(Player except) {
        String prefix = prefix().toLowerCase(Locale.ROOT);
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p != except && p.getName().toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(Comparator.comparing(Player::getName))
                .limit(required())
                .map(p -> (Player) p)
                .toList();
    }

    // ------------------------------------------------------------------ controls

    public StartResult start(Player viewer) {
        if (running()) {
            return StartResult.RUNNING;
        }
        List<Player> found = findBots(viewer);
        if (found.size() < required()) {
            return StartResult.NO_BOTS;
        }
        this.viewer = viewer;
        this.bots = found;
        this.names = found.stream().map(Player::getName).toList();
        expected = null;
        lanes = List.of();
        needSquad = false;
        missingFor = 0;
        placeTries = 0;
        introduced = null;
        paused = false;
        index = 0;
        delay = 0;
        waited = 0;
        waiting = null;
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
        if (introduced != null) {
            Step step = introduced;
            introduced = null;
            begin(step);
            return;
        }
        if (index >= steps.size()) {
            finish(Component.text("Tour complete", NamedTextColor.GREEN),
                    Component.text("Run /se demo tour again to replay", NamedTextColor.GRAY));
            return;
        }
        Step step = steps.get(index);
        if (step.title().isEmpty()) {
            index++;
            begin(step);
            return;
        }
        if (!ensureReady()) {
            return;
        }
        index++;
        show(step.title(), step.subtitle());
        introduced = step;
        delay = INTRO_TICKS;
    }

    /** Runs a step's action once its title, if it had one, has gone and the pause after it is over. */
    private void begin(Step step) {
        step.action().run();
        if (step.until() != null) {
            waiting = step;
            waited = 0;
        } else {
            delay = step.hold();
        }
    }

    /**
     * Checked before every stage: all test players are online and standing where they should be. A player who is
     * out of place is put back and the viewer is told. A stage never starts with someone missing.
     */
    private boolean ensureReady() {
        List<Player> current = new ArrayList<>();
        for (String name : names) {
            Player bot = plugin.getServer().getPlayerExact(name);
            if (bot == null) {
                if (missingFor % 100 == 0) {
                    problem(name + " is not online, waiting for it to come back");
                }
                if (++missingFor > MISSING_TIMEOUT) {
                    abort(name + " never came back");
                }
                return false;
            }
            current.add(bot);
        }
        missingFor = 0;
        bots = current;
        boolean ok = true;
        if (expected != null) {
            for (int i = 0; i < bots.size(); i++) {
                if (!near(bots.get(i), expected.get(i))) {
                    ok = false;
                    problem(bots.get(i).getName() + " was out of place, moving it back");
                    bots.get(i).teleport(expected.get(i));
                }
            }
        }
        if (needSquad && !everyoneJoined()) {
            ok = false;
            problem("someone was not in squad " + SQUAD + ", adding them");
            joinStragglers();
        }
        if (!ok) {
            if (++placeTries > 3) {
                abort("a test player could not be put in place");
            }
            delay = 10;
            return false;
        }
        placeTries = 0;
        return true;
    }

    private boolean near(Player bot, Location spot) {
        Location at = bot.getLocation();
        return at.getWorld().equals(spot.getWorld()) && Math.abs(at.getY() - spot.getY()) <= 2
                && Math.hypot(at.getX() - spot.getX(), at.getZ() - spot.getZ()) <= IN_PLACE;
    }

    private void problem(String text) {
        plugin.getLogger().warning("Tour: " + text);
        if (viewer != null && viewer.isOnline()) {
            viewer.sendMessage(Component.text("[Tour] " + text, NamedTextColor.RED));
        }
    }

    private void abort(String reason) {
        problem("stopping: " + reason);
        finish(Component.text("Tour stopped", NamedTextColor.RED), Component.text(reason, NamedTextColor.GRAY));
    }

    private boolean inPlace() {
        if (expected == null) {
            return true;
        }
        for (int i = 0; i < bots.size(); i++) {
            if (!bots.get(i).isOnline() || !near(bots.get(i), expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void show(String title, String subtitle) {
        viewer.showTitle(Title.title(Component.text(title, NamedTextColor.GOLD),
                Component.text(subtitle, NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(FADE_IN), Duration.ofMillis(STAY), Duration.ofMillis(FADE_OUT))));
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
        add(stageTitle(1, total, "Formation"), bots.size() + " test players line up", this::formUp, 0);
        addAndWait("", "", () -> { }, this::inPlace, 60, 60);

        add(stageTitle(2, total, "Join block"), "Step on obsidian to join " + SQUAD, this::prepareJoining, 30);
        addAndWait("", "", () -> sendWalkCue(bots), this::allArrived, 0, JOIN_TIMEOUT);
        add("", "", this::retryWalkers, 0);
        addAndWait("", "", () -> { }, this::allArrived, 0, 100);
        add("", "", this::placeStragglers, 40);

        equipAndWait(stageTitle(3, total, "Sync Equip"), "Armor, sword and shield in sync", 50);

        for (int speed : new int[] {1, 2, 4, 6}) {
            String unit = speed == 1 ? "tick" : "ticks";
            addAndWait(stageTitle(4, total, "Speed " + speed), "Off, then on, " + speed + " " + unit + " a piece",
                    () -> {
                        run("squad speed " + speed);
                        run("squad unequip");
                    }, this::idle, 30, SEQUENCE_TIMEOUT);
            equipAndWait("", "", 50);
        }
        add("", "", () -> run("squad speed default"), 5);

        unequipAndWait(stageTitle(5, total, "Loadout"), "Totem, axe and no helmet", 30);
        add("", "", this::customLoadout, 10);
        equipAndWait("", "", 60);

        unequipAndWait(stageTitle(6, total, "Unequip"), "Everyone takes gear off together", 40);
        add("", "", () -> run("squad loadout reset"), 10);

        add(stageTitle(7, total, "Swapping"), "Axe, pickaxe, totem, old boots", this::awkwardInventories, 50);
        equipAndWait("", "", 60);

        unequipAndWait(stageTitle(8, total, "Finale"), "One last full equip, in step", 30);
        add("", "", () -> {
            run("squad loadout reset");
            giveKits();
        }, 10);
        equipAndWait("", "", 100);
    }

    private static String stageTitle(int number, int total, String name) {
        return number + "/" + total + " — " + name;
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
        List<Location> slots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int offset = 2 * i - (count - 1);
            int x = origin.getX() + fx * LINE_DISTANCE - fz * offset;
            int z = origin.getZ() + fz * LINE_DISTANCE + fx * offset;
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location at = new Location(world, x + 0.5, y, z + 0.5, yaw, 0);
            slots.add(at);
            bots.get(i).teleport(at);
        }
        expected = slots;
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
        float yaw = (float) Math.toDegrees(Math.atan2(fx, -fz));
        List<Location> spots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int offset = 2 * i - (count - 1);
            for (int depth = 0; depth < STRIP_DEPTH; depth++) {
                int distance = STRIP_START - depth;
                int x = origin.getX() + fx * distance - fz * offset;
                int z = origin.getZ() + fz * distance + fx * offset;
                Block ground = world.getBlockAt(x, world.getHighestBlockYAt(x, z), z);
                changedBlocks.putIfAbsent(ground.getLocation(), ground.getBlockData());
                ground.setType(Material.OBSIDIAN, false);
                if (depth == STRIP_DEPTH / 2) {
                    spots.add(new Location(world, x + 0.5, ground.getY() + 1, z + 0.5, yaw, 0));
                }
            }
        }
        lanes = spots;
        run("squad create " + SQUAD);
        run("squad block set obsidian");
    }

    /** Tells these bots to walk to the middle of their own obsidian lane. The leader walks too. */
    private void sendWalkCue(List<Player> who) {
        for (Player bot : who) {
            Location to = lanes.get(bots.indexOf(bot));
            bot.sendMessage(Component.text(CUE + "goto " + to.getX() + " " + to.getZ()));
        }
    }

    private boolean everyoneJoined() {
        return bots.stream().allMatch(b -> plugin.squads().squadOf(b.getUniqueId()).isPresent());
    }

    private boolean arrived(Player bot) {
        return bot.isOnline() && plugin.squads().squadOf(bot.getUniqueId()).isPresent()
                && bot.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getType() == Material.OBSIDIAN;
    }

    private boolean allArrived() {
        return bots.stream().allMatch(this::arrived);
    }

    private List<Player> notArrived() {
        return bots.stream().filter(b -> !arrived(b)).toList();
    }

    /** First recovery: anyone who did not make it onto the obsidian is told to walk again. */
    private void retryWalkers() {
        List<Player> stuck = notArrived();
        if (!stuck.isEmpty()) {
            problem(stuck.stream().map(Player::getName).toList() + " did not reach the obsidian, sending them again");
            sendWalkCue(stuck);
        }
    }

    /** Second recovery: whoever is still not there is moved onto the obsidian and added to the squad. */
    private void placeStragglers() {
        for (Player bot : notArrived()) {
            problem(bot.getName() + " could not walk onto the obsidian, placing it there");
            bot.teleport(lanes.get(bots.indexOf(bot)));
        }
        joinStragglers();
        expected = lanes;
        needSquad = true;
    }

    /** A bot that is not in the squad yet is let in the same way stepping on the block does. */
    private void joinStragglers() {
        plugin.squads().profile(SQUAD).ifPresent(profile -> {
            for (Player bot : bots) {
                if (plugin.squads().squadOf(bot.getUniqueId()).isEmpty()) {
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
