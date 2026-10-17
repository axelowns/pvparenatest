package dev.pvparena;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Match {
    enum State { GRIND, TRANSITION, COUNTDOWN, FIGHT, ENDING }

    record Participant(UUID id, String name, Location spawn) {}

    final MatchType type;
    final World world;
    final Location center;
    final WorldBorder border;
    final double radius;

    /** Everyone still connected to the match (including eliminated spectators). */
    final Map<UUID, Participant> participants = new LinkedHashMap<>();
    /** Players still in the running. */
    final Set<UUID> alive = new LinkedHashSet<>();

    final BossBar bar = BossBar.bossBar(Text.mm("<red><bold>Gather gear"), 1f,
            BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);

    State state = State.GRIND;
    int secondsLeft;
    int totalGrind;
    int fightSeconds;
    double halfSize;
    Location fightCenter;
    BukkitTask task;

    Match(MatchType type, World world, Location center, WorldBorder border, double radius) {
        this.type = type;
        this.world = world;
        this.center = center;
        this.border = border;
        this.radius = radius;
        this.halfSize = radius;
    }

    /** Distance from the arena center in the border's (square) metric. */
    double cheb(double x, double z) {
        return Math.max(Math.abs(x - center.getX()), Math.abs(z - center.getZ()));
    }

    List<Player> onlineParticipants() {
        List<Player> list = new ArrayList<>();
        for (UUID id : participants.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) list.add(p);
        }
        return list;
    }

    List<Player> onlineAlive() {
        List<Player> list = new ArrayList<>();
        for (Player p : onlineParticipants()) {
            if (alive.contains(p.getUniqueId())) list.add(p);
        }
        return list;
    }

    void message(Component c) {
        for (Player p : onlineParticipants()) p.sendMessage(c);
    }

    void sound(Sound s, float pitch) {
        for (Player p : onlineParticipants()) p.playSound(p.getLocation(), s, 1f, pitch);
    }
}
