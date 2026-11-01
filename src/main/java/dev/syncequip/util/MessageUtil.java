package dev.syncequip.util;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MessageUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private YamlConfiguration messages = new YamlConfiguration();

    public MessageUtil(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled messages.yml", e);
        }
        messages = loaded;
    }

    public static TagResolver ph(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver ph(String name, Component value) {
        return Placeholder.component(name, value);
    }

    public String raw(String key) {
        // getString(key) falls back to the bundled defaults, getString(key, fallback) would not.
        String value = messages.getString(key);
        return value != null ? value : "<red>Missing message: " + key;
    }

    public Component render(String key, TagResolver... placeholders) {
        return MINI.deserialize(raw(key), placeholders);
    }

    public void sendList(Audience audience, String key) {
        List<String> lines = messages.getStringList(key);
        for (String line : lines) {
            audience.sendMessage(MINI.deserialize(line));
        }
    }

    public void send(Audience audience, String key, TagResolver... placeholders) {
        String raw = raw(key);
        if (raw.isEmpty()) {
            return;
        }
        Component prefix = MINI.deserialize(raw("prefix"));
        audience.sendMessage(prefix.append(MINI.deserialize(raw, placeholders)));
    }

    public void sendPlain(Audience audience, String key, TagResolver... placeholders) {
        audience.sendMessage(render(key, placeholders));
    }

    public void actionBar(Audience audience, String key, TagResolver... placeholders) {
        audience.sendActionBar(render(key, placeholders));
    }

    public void title(Audience audience, String titleKey, String subtitleKey, TagResolver... placeholders) {
        Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(150));
        Component subtitle = subtitleKey == null ? Component.empty() : render(subtitleKey, placeholders);
        audience.showTitle(Title.title(render(titleKey, placeholders), subtitle, times));
    }
}
