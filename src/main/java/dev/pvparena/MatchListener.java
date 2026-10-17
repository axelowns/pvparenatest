package dev.pvparena;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class MatchListener implements Listener {
    private final PvpArenaPlugin plugin;

    MatchListener(PvpArenaPlugin plugin) {
        this.plugin = plugin;
    }

    private MatchManager mgr() {
        return plugin.matches();
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        mgr().onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mgr().onQuit(e.getPlayer());
    }

    // ------------------------------------------------------------------ damage

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = mgr().matchOf(p);
        if (m == null) return;
        switch (m.state) {
            case TRANSITION, COUNTDOWN, ENDING -> e.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Match m = mgr().matchOf(victim);
        if (m == null) return;

        Player attacker = attackerOf(e.getDamager());
        if (attacker == null) return;
        if (mgr().matchOf(attacker) != m) {
            e.setCancelled(true);
            return;
        }
        if (m.state == Match.State.GRIND && !plugin.getConfig().getBoolean("pvp-during-grind", false)) {
            e.setCancelled(true);
        }
    }

    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    // ------------------------------------------------------------------ death / respawn

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Match m = mgr().matchOf(p);
        if (m == null) return;

        Component msg = e.deathMessage();
        e.deathMessage(null);
        if (msg != null) m.message(msg);

        if (m.state == Match.State.FIGHT) mgr().eliminate(m, p.getUniqueId());

        // Skip the death screen.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.isDead()) p.spigot().respawn();
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Match m = mgr().matchOf(p);
        if (m == null) return;
        UUID id = p.getUniqueId();
        Match.Participant part = m.participants.get(id);

        if (m.alive.contains(id) && part != null) {
            // Died while gathering gear: back to the personal starting spot with an empty inventory.
            e.setRespawnLocation(part.spawn());
            Bukkit.getScheduler().runTask(plugin, () -> p.setWorldBorder(m.border));
        } else {
            // Eliminated: spectate the rest of the fight.
            Location c = (m.fightCenter != null ? m.fightCenter : m.center).clone().add(0, 12, 0);
            e.setRespawnLocation(c);
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.setGameMode(GameMode.SPECTATOR);
                p.setWorldBorder(m.border);
            });
        }
    }

    // ------------------------------------------------------------------ movement / boundaries

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) return;
        Player p = e.getPlayer();
        Match m = mgr().matchOf(p);
        if (m == null) return;
        Location from = e.getFrom();
        Location to = e.getTo();

        // Freeze everyone while the final fight is being set up and counted down.
        if ((m.state == Match.State.TRANSITION || m.state == Match.State.COUNTDOWN)
                && m.alive.contains(p.getUniqueId())
                && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
            e.setCancelled(true);
            return;
        }

        // Hard barrier: never allow moving further out than the allowed area (moving inward is always fine).
        double dTo = m.cheb(to.getX(), to.getZ());
        if (dTo > m.halfSize && dTo > m.cheb(from.getX(), from.getZ())) {
            e.setCancelled(true);
            p.sendActionBar(Text.mm("<red>You have reached the edge of the arena."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Match m = mgr().matchOf(p);
        if (m == null) return;
        Location to = e.getTo();

        switch (e.getCause()) {
               case ENDER_PEARL, CONSUMABLE_EFFECT -> {
                if (!to.getWorld().equals(m.world) || m.cheb(to.getX(), to.getZ()) > m.halfSize) {
                    e.setCancelled(true);
                }
            }
            case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> e.setCancelled(true);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ commands

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (mgr().matchOf(p) == null || p.hasPermission("pvparena.bypass")) return;

        String label = e.getMessage().substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        List<String> allowed = plugin.getConfig().getStringList("allowed-commands");
        for (String a : allowed) {
            if (a.equalsIgnoreCase(label)) return;
        }
        e.setCancelled(true);
        p.sendMessage(Text.msg("<red>You can't use that command during a match."));
    }
}
