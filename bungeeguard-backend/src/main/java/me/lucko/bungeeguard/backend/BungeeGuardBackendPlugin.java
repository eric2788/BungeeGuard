package me.lucko.bungeeguard.backend;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Simple plugin which re-implements the BungeeCord handshake protocol, and cancels all attempts
 * which don't contain the special token set by the proxy.
 * <p>
 * The token is included within the player's profile properties, but removed during the handshake.
 */
public class BungeeGuardBackendPlugin extends JavaPlugin implements Listener {
    private static final Type PROPERTY_LIST_TYPE = new TypeToken<List<JsonObject>>() {
    }.getType();

    private final Gson gson = new Gson();

    private String noDataKickMessage;
    private String noPropertiesKickMessage;
    private String invalidTokenKickMessage;

    private Set<String> allowedTokens;

    private Class<?> getCraftPlayerCls() throws ClassNotFoundException {
        String version = Bukkit.getServer().getClass().getPackage().getName().replace(".", ",").split(",")[3] + ".";
        String name = "org.bukkit.craftbukkit." + version + "entity.CraftPlayer";
        return Class.forName(name);
    }

    @Override
    public void onEnable() {
        getLogger().info("Using Paper PlayerHandshakeEvent");
        getServer().getPluginManager().registerEvents(this, this);

        saveDefaultConfig();
        FileConfiguration config = getConfig();

        this.noDataKickMessage = ChatColor.translateAlternateColorCodes('&', config.getString("no-data-kick-message"));
        this.noPropertiesKickMessage = ChatColor.translateAlternateColorCodes('&', config.getString("no-properties-kick-message"));
        this.invalidTokenKickMessage = ChatColor.translateAlternateColorCodes('&', config.getString("invalid-token-kick-message"));
        this.allowedTokens = new HashSet<>(config.getStringList("allowed-tokens"));
    }


    @EventHandler
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent e) {
        if (Bukkit.getPlayer(e.getUniqueId()).isOnline()) {
            e.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
            e.setKickMessage("You have already in proxy.");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHandshake(PlayerLoginEvent e) {
        try {
            Class<?> craftPlayerCls = getCraftPlayerCls();
            Object craftPlayer = craftPlayerCls.cast(e.getPlayer());
            Method getHandleMethod = craftPlayerCls.getMethod("getHandle");
            Object entityPlayer = getHandleMethod.invoke(craftPlayer);
            Method profileMethod = entityPlayer.getClass().getMethod("getProfile");
            GameProfile profile = (GameProfile) profileMethod.invoke(entityPlayer);
            PropertyMap map = profile.getProperties();
            String uniqueId = e.getPlayer().getUniqueId().toString();
            String socketAddressHostname = e.getHostname();

            // fail if no properties
            if (map.isEmpty()) {
                getLogger().warning("Denied connection from " + uniqueId + " @ " + socketAddressHostname + " - No properties were sent in their handshake.");
                e.setKickMessage(this.noPropertiesKickMessage);
                e.setResult(PlayerLoginEvent.Result.KICK_OTHER);
                return;
            }

            String token = null;

            //System.out.println(gson.toJson(map.asMap()));

            // try to find the token

            for (Property property : map.get("bungeeguard-token")) {
                if (property != null) {
                    token = property.getValue();
                    break;
                }
            }

            //System.out.println(token);

            // deny connection if no token was provided
            if (token == null) {
                getLogger().warning("Denied connection from " + uniqueId + " @ " + socketAddressHostname + " - A token was not included in their handshake properties.");
                e.setKickMessage(this.noDataKickMessage);
                e.setResult(PlayerLoginEvent.Result.KICK_OTHER);
                return;
            }

            if (this.allowedTokens.isEmpty()) {
                getLogger().info("No token configured. Saving the one from the connection " + uniqueId + " @ " + socketAddressHostname + " to the config!");
                this.allowedTokens.add(token);
                getConfig().set("allowed-tokens", new ArrayList<>(this.allowedTokens));
                saveConfig();
            } else if (!this.allowedTokens.contains(token)) {
                getLogger().warning("Denied connection from " + uniqueId + " @ " + socketAddressHostname + " - An invalid token was used: " + token);
                e.setKickMessage(this.invalidTokenKickMessage);
                e.setResult(PlayerLoginEvent.Result.KICK_OTHER);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

}
