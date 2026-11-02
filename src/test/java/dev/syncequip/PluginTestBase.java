package dev.syncequip;

import dev.syncequip.commands.SyncEquipCommand;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public abstract class PluginTestBase {
    protected static final EquipmentSlot[] SEQUENCE_SLOTS = {EquipmentSlot.FEET, EquipmentSlot.LEGS,
            EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.OFF_HAND};

    protected ServerMock server;
    protected SyncEquipPlugin plugin;
    protected SyncEquipCommand command;

    @BeforeEach
    void startServer() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(SyncEquipPlugin.class);
        command = new SyncEquipCommand(plugin);
    }

    @AfterEach
    void stopServer() {
        MockBukkit.unmock();
    }

    protected PlayerMock playerWithKit(String name) {
        PlayerMock player = server.addPlayer(name);
        giveKit(player);
        return player;
    }

    protected static void giveKit(PlayerMock player) {
        PlayerInventory inventory = player.getInventory();
        inventory.setHeldItemSlot(0);
        inventory.setItem(0, new ItemStack(Material.IRON_SWORD));
        inventory.setItem(9, new ItemStack(Material.IRON_BOOTS));
        inventory.setItem(10, new ItemStack(Material.IRON_LEGGINGS));
        inventory.setItem(11, new ItemStack(Material.IRON_CHESTPLATE));
        inventory.setItem(12, new ItemStack(Material.IRON_HELMET));
        inventory.setItem(13, new ItemStack(Material.SHIELD));
    }

    protected void run(PlayerMock player, String... args) {
        command.handle(player, args);
    }

    protected void tick(int ticks) {
        server.getScheduler().performTicks(ticks);
    }

    protected long now() {
        return server.getScheduler().getCurrentTick();
    }

    protected static boolean has(PlayerMock player, EquipmentSlot slot) {
        ItemStack item = player.getInventory().getItem(slot);
        return item != null && !item.isEmpty();
    }

    protected static int countEquipped(PlayerMock player) {
        int count = 0;
        for (EquipmentSlot slot : SEQUENCE_SLOTS) {
            if (has(player, slot)) {
                count++;
            }
        }
        return count;
    }

    protected static int totalItems(PlayerMock player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.isEmpty()) {
                total += item.getAmount();
            }
        }
        return total;
    }

    protected static List<String> messages(PlayerMock player) {
        List<String> result = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            result.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return result;
    }

    protected static boolean received(PlayerMock player, String fragment) {
        return messages(player).stream().anyMatch(m -> m.contains(fragment));
    }

    protected void setConfig(String path, Object value) {
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        plugin.reloadSettings();
    }
}
