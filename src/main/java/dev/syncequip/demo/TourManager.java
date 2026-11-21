package dev.syncequip.demo;

import dev.syncequip.SyncEquipPlugin;
import dev.syncequip.synchronization.FinishReason;
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
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

/** Scripted walkthrough driven by test players through the ordinary commands. Everything it changes is put back by {@link #cleanup}. */
public final class TourManager {
    public static final String CUE = "[SE-TOUR] ";
    public static final String SQUAD = "TestGroup";
    private static final int DEFAULT_BOTS = 5;
    private static final int LINE_DISTANCE = 12;
    private static final int STRIP_START = 8;
    private static final int STRIP_DEPTH = 3;
    private static final int STAGES = 5;
    private static final int FADE_IN = 100;
    private static final int STAY = 400;
    private static final int FADE_OUT = 200;
    /** A title is on screen for 0.7 s and the stage's action starts the moment it is gone. */
    private static final int INTRO_TICKS = (FADE_IN + STAY + FADE_OUT) / 50;
    private static final int SLOW_SPEED = 10;
    private static final int FAST_SPEED = 1;
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
    private boolean customLoadoutSet;

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
        customLoadoutSet = false;
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

    public void shutdown() {
        if (running()) {
            task.cancel();
            task = null;
            cleanup();
        }
    }

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

    private void begin(Step step) {
        step.action().run();
        if (step.until() != null) {
            waiting = step;
            waited = 0;
        } else {
            delay = step.hold();
        }
    }

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

    /** The tour starts its sequences straight away; the configured countdown and cooldown are for real players. */
    private void equipAndWait(String title, String subtitle, int hold, Runnable before) {
        addAndWait(title, subtitle, () -> {
            before.run();
            restock();
            startSequence(true);
        }, this::idle, hold, SEQUENCE_TIMEOUT);
    }

    private void unequipAndWait(String title, String subtitle, int hold, Runnable before) {
        addAndWait(title, subtitle, () -> {
            before.run();
            startSequence(false);
        }, this::idle, hold, SEQUENCE_TIMEOUT);
    }

    /** A bot that just equipped is on the normal cooldown, which would silently refuse the next sequence. */
    private void startSequence(boolean equip) {
        bots.forEach(b -> plugin.cooldowns().clear(b.getUniqueId()));
        boolean started = equip ? plugin.squadController().start(leader(), 0) : plugin.squadController().unequip(leader(), 0);
        if (!started) {
            problem("the squad " + (equip ? "equip" : "unequip") + " did not start, skipping it");
        }
    }

    /** Shows how fast the next sequence is, for as long as it runs. */
    private void note(String text) {
        if (viewer != null && viewer.isOnline()) {
            viewer.sendActionBar(Component.text(text, NamedTextColor.YELLOW));
            viewer.sendMessage(Component.text("[Tour] " + text, NamedTextColor.GOLD));
        }
    }

    private void buildSteps() {
        Runnable nothing = () -> { };
        add("Squad Formation", subtitle(1, bots.size() + " test players line up"), this::formUp, 0);
        addAndWait("", "", nothing, this::inPlace, 8, 60);

        add("Block Join", subtitle(2, "Obsidian joins " + SQUAD), this::prepareJoining, 0);
        addAndWait("", "", () -> sendWalkCue(bots), this::allArrived, 0, JOIN_TIMEOUT);
        add("", "", this::retryWalkers, 0);
        addAndWait("", "", nothing, this::allArrived, 0, 100);
        add("", "", this::placeStragglers, 6);

        equipAndWait("Synchronized Equip", subtitle(3, "Armor, sword and shield"), 26, nothing);

        // Speed Control: clear everything, equip slowly, clear again, equip fast. Each equip is a real squad sequence.
        unequipAndWait("Speed Control", subtitle(4, "Slow: " + pace(SLOW_SPEED)), 10,
                () -> run("squad speed default"));
        equipAndWait("", "", 16, () -> {
            run("squad speed " + SLOW_SPEED);
            note("Slow: " + pace(SLOW_SPEED));
        });
        unequipAndWait("", "", 8, () -> run("squad speed default"));
        equipAndWait("Speed Control", subtitle(4, "Fast: " + pace(FAST_SPEED)), 24, () -> {
            run("squad speed " + FAST_SPEED);
            note("Fast: " + pace(FAST_SPEED));
        });

        // Custom Loadout: clear, switch the squad loadout, equip it, let it be seen, clear it again.
        unequipAndWait("Custom Loadout", subtitle(5, "Axe, totem, no helmet"), 6,
                () -> run("squad speed default"));
        add("", "", this::customLoadout, 2);
        equipAndWait("", "", 50, nothing);
        unequipAndWait("", "", 14, nothing);
    }

    private static String pace(int ticks) {
        return ticks + (ticks == 1 ? " tick" : " ticks") + " per piece";
    }

    private static String subtitle(int number, String text) {
        return number + "/" + STAGES + " · " + text;
    }

    private void formUp() {
        cleanupSquad();
        giveKits();
        List<Location> slots = new ArrayList<>();
        for (int i = 0; i < bots.size(); i++) {
            Location at = standingOn(groundAt(LINE_DISTANCE, i));
            slots.add(at);
            bots.get(i).teleport(at);
        }
        expected = slots;
    }

    private Block groundAt(int distance, int lane) {
        int[] facing = cardinal(viewer.getLocation().getYaw());
        int offset = 2 * lane - (bots.size() - 1);
        Block origin = viewer.getLocation().getBlock();
        int x = origin.getX() + facing[0] * distance - facing[1] * offset;
        int z = origin.getZ() + facing[1] * distance + facing[0] * offset;
        World world = viewer.getWorld();
        return world.getBlockAt(x, world.getHighestBlockYAt(x, z), z);
    }

    private Location standingOn(Block ground) {
        int[] facing = cardinal(viewer.getLocation().getYaw());
        float yaw = (float) Math.toDegrees(Math.atan2(facing[0], -facing[1]));
        return ground.getLocation().add(0.5, 1, 0.5).setRotation(yaw, 0);
    }

    private static int[] cardinal(float yaw) {
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians);
        double dz = Math.cos(radians);
        return Math.abs(dx) > Math.abs(dz) ? new int[] {dx > 0 ? 1 : -1, 0} : new int[] {0, dz > 0 ? 1 : -1};
    }

    private void prepareJoining() {
        List<Location> spots = new ArrayList<>();
        for (int lane = 0; lane < bots.size(); lane++) {
            for (int depth = 0; depth < STRIP_DEPTH; depth++) {
                Block ground = groundAt(STRIP_START - depth, lane);
                changedBlocks.putIfAbsent(ground.getLocation(), ground.getBlockData());
                ground.setType(Material.OBSIDIAN, false);
                if (depth == STRIP_DEPTH / 2) {
                    spots.add(standingOn(ground));
                }
            }
        }
        lanes = spots;
        run("squad create " + SQUAD);
        run("squad block set obsidian");
    }

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
                && bot.getLocation().getBlock().getRelative(BlockFace.DOWN).getType() == Material.OBSIDIAN;
    }

    private boolean allArrived() {
        return bots.stream().allMatch(this::arrived);
    }

    private List<Player> notArrived() {
        return bots.stream().filter(b -> !arrived(b)).toList();
    }

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
        customLoadoutSet = true;
        bots.forEach(this::giveCustomItems);
        run("squad loadout set offhand totem_of_undying");
        run("squad loadout set mainhand any_axe");
        run("squad loadout set helmet none");
    }

    private void giveCustomItems(Player bot) {
        PlayerInventory inv = bot.getInventory();
        if (!inv.contains(Material.NETHERITE_AXE)) {
            inv.setItem(14, new ItemStack(Material.NETHERITE_AXE));
        }
        if (!inv.contains(Material.TOTEM_OF_UNDYING) && inv.getItemInOffHand().getType() != Material.TOTEM_OF_UNDYING) {
            inv.setItem(15, new ItemStack(Material.TOTEM_OF_UNDYING));
        }
    }

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

    /** A bot that reconnected or lost an item mid-tour gets what the next sequence needs, so nothing is skipped. */
    private void restock() {
        for (Player bot : bots) {
            for (Material needed : kit()) {
                if (!carries(bot, needed)) {
                    giveKit(bot);
                    if (customLoadoutSet) {
                        giveCustomItems(bot);
                    }
                    break;
                }
            }
        }
    }

    private static List<Material> kit() {
        return List.of(Material.NETHERITE_SWORD, Material.NETHERITE_BOOTS, Material.NETHERITE_LEGGINGS,
                Material.NETHERITE_CHESTPLATE, Material.NETHERITE_HELMET, Material.SHIELD);
    }

    private static boolean carries(Player bot, Material material) {
        PlayerInventory inv = bot.getInventory();
        return inv.contains(material) || inv.getItemInOffHand().getType() == material
                || java.util.Arrays.stream(inv.getArmorContents()).anyMatch(i -> i != null && i.getType() == material);
    }

    private void cleanupSquad() {
        plugin.squads().disband(SQUAD);
    }

    private void cleanup() {
        for (Player bot : bots) {
            plugin.sequenceService().scheduler().findByParticipant(bot.getUniqueId())
                    .ifPresent(s -> s.cancel(FinishReason.CANCELLED));
        }
        cleanupSquad();
        for (Player bot : bots) {
            plugin.cooldowns().clear(bot.getUniqueId());
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
