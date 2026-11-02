package dev.syncequip;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Helpers for squads, joining blocks and large groups. */
public abstract class V11TestBase extends PluginTestBase {
    protected World world;

    @BeforeEach
    void v11Setup() {
        world = server.getWorld("world") != null ? server.getWorld("world") : server.addSimpleWorld("world");
        setConfig("synchronization.countdown-seconds", 0);
        setConfig("synchronization.unequip-countdown-seconds", 0);
        setConfig("controls.cooldown-seconds", 0);
        setConfig("synchronization.max-squad-size", 200);
    }

    /** Puts a stone block at the coordinates and returns it. */
    protected Block stone(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        return block;
    }

    protected static Location standingOn(Block block) {
        return new Location(block.getWorld(), block.getX() + 0.5, block.getY() + 1, block.getZ() + 0.5);
    }

    /** Teleports the player onto the block and fires the move event a real step would. */
    protected PlayerMoveEvent stepOn(PlayerMock player, Block block) {
        Location from = standingOn(block).add(0, 0, 3);
        Location to = standingOn(block);
        player.teleport(from);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
        server.getPluginManager().callEvent(event);
        player.teleport(to);
        return event;
    }

    protected PlayerMock leaderWithSquad(String leaderName, String squadName) {
        PlayerMock leader = playerWithKit(leaderName);
        run(leader, "squad", "create", squadName);
        messages(leader);
        return leader;
    }

    /** Creates a squad whose members were all invited and joined the normal way, each with the iron kit. */
    protected List<PlayerMock> formSquad(String squadName, int size) {
        List<PlayerMock> squad = new ArrayList<>();
        PlayerMock leader = leaderWithSquad(squadName + "L", squadName);
        squad.add(leader);
        for (int i = 1; i < size; i++) {
            PlayerMock member = playerWithKit(squadName + "M" + i);
            run(leader, "squad", "invite", member.getName());
            run(member, "squad", "join", squadName);
            squad.add(member);
        }
        squad.forEach(PluginTestBase::messages);
        return squad;
    }

    protected boolean isMemberOf(PlayerMock player, String squadName) {
        return plugin.squads().squadOf(player.getUniqueId()).map(s -> s.name().equalsIgnoreCase(squadName)).orElse(false);
    }

    /** Performs one tick at a time and records the tick on which each tracked slot became filled. */
    protected Map<UUID, Map<EquipmentSlot, Long>> recordFilled(List<PlayerMock> players, int ticks) {
        Map<UUID, Map<EquipmentSlot, Long>> result = new LinkedHashMap<>();
        for (int i = 0; i < ticks; i++) {
            server.getScheduler().performOneTick();
            for (PlayerMock player : players) {
                for (EquipmentSlot slot : SEQUENCE_SLOTS) {
                    if (has(player, slot)) {
                        result.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(EquipmentSlot.class))
                                .putIfAbsent(slot, now());
                    }
                }
            }
        }
        return result;
    }

    /** Same, but records when each slot became empty. Slots must be filled when recording starts. */
    protected Map<UUID, Map<EquipmentSlot, Long>> recordEmptied(List<PlayerMock> players, int ticks) {
        Map<UUID, Map<EquipmentSlot, Long>> result = new LinkedHashMap<>();
        for (int i = 0; i < ticks; i++) {
            server.getScheduler().performOneTick();
            for (PlayerMock player : players) {
                for (EquipmentSlot slot : SEQUENCE_SLOTS) {
                    if (!has(player, slot)) {
                        result.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(EquipmentSlot.class))
                                .putIfAbsent(slot, now());
                    }
                }
            }
        }
        return result;
    }
}
