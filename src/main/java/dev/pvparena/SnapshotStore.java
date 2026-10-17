package dev.pvparena;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Saves a player's normal state (inventory, position, ...) to disk before a match
 * and puts it back afterwards. Because it lives on disk, it also survives crashes and
 * players disconnecting mid-match: it is restored the next time they join.
 */
final class SnapshotStore {
    private final JavaPlugin plugin;
    private final File dir;

    SnapshotStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "snapshots");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    private File file(UUID id) {
        return new File(dir, id + ".yml");
    }

    boolean has(UUID id) {
        return file(id).exists();
    }

    void save(Player p) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("location", p.getLocation());
        y.set("gamemode", p.getGameMode().name());
        y.set("health", p.getHealth());
        y.set("food", p.getFoodLevel());
        y.set("saturation", p.getSaturation());
        y.set("level", p.getLevel());
        y.set("exp", p.getExp());
        y.set("contents", Arrays.asList(p.getInventory().getContents()));
        y.set("effects", new ArrayList<>(p.getActivePotionEffects()));
        try {
            y.save(file(p.getUniqueId()));
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save snapshot for " + p.getName(), ex);
        }
    }

    /** Restores the saved state and teleports the player back. Returns false if no snapshot exists. */
    boolean restore(Player p, boolean syncTeleport) {
        File f = file(p.getUniqueId());
        if (!f.exists()) return false;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());

        List<?> raw = y.getList("contents", List.of());
        ItemStack[] items = new ItemStack[raw.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = raw.get(i) instanceof ItemStack s ? s : null;
        }
        p.getInventory().clear();
        p.getInventory().setContents(items);

        for (Object o : y.getList("effects", List.of())) {
            if (o instanceof PotionEffect e) p.addPotionEffect(e);
        }

        AttributeInstance maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr != null ? maxAttr.getValue() : 20.0;
        p.setHealth(Math.max(1.0, Math.min(y.getDouble("health", max), max)));
        p.setFoodLevel(y.getInt("food", 20));
        p.setSaturation((float) y.getDouble("saturation", 5.0));
        p.setLevel(y.getInt("level", 0));
        p.setExp((float) y.getDouble("exp", 0.0));
        p.setFireTicks(0);
        p.setFallDistance(0f);

        try {
            p.setGameMode(GameMode.valueOf(y.getString("gamemode", "SURVIVAL")));
        } catch (IllegalArgumentException ex) {
            p.setGameMode(GameMode.SURVIVAL);
        }

        Location loc = y.getLocation("location");
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (loc != null && loc.getWorld() != null) {
            if (syncTeleport) p.teleport(loc);
            else p.teleportAsync(loc);
        }
        return true;
    }
}
