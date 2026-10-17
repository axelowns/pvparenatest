package dev.pvparena;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

final class MatchManager {
    private static final int[] GRIND_ANNOUNCE = {900, 600, 300, 120, 60, 30, 10};

    private final PvpArenaPlugin plugin;
    private final QueueManager queue = new QueueManager();
    private final SnapshotStore snapshots;
    private final Map<UUID, Match> byPlayer = new HashMap<>();
    private final Set<Match> matches = new LinkedHashSet<>();
    private final List<Location> reservedCenters = new ArrayList<>();
    private final Executor mainThread;
    private World world;
    private int starting = 0;

    MatchManager(PvpArenaPlugin plugin) {
        this.plugin = plugin;
        this.snapshots = new SnapshotStore(plugin);
        this.mainThread = r -> {
            if (Bukkit.isPrimaryThread()) r.run();
            else Bukkit.getScheduler().runTask(plugin, r);
        };
    }

    // ------------------------------------------------------------------ setup / accessors

    void loadWorld() {
        String name = plugin.getConfig().getString("world", "pvp_world");
        World w = Bukkit.getWorld(name);
        if (w == null) w = new WorldCreator(name).environment(World.Environment.NORMAL).createWorld();
        if (w == null) throw new IllegalStateException("Could not load or create world '" + name + "'");
        w.setDifficulty(Difficulty.NORMAL);
        world = w;
    }

    QueueManager queue() {
        return queue;
    }

    Match matchOf(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    boolean isInMatch(Player p) {
        return byPlayer.containsKey(p.getUniqueId());
    }

    int activeCount() {
        return matches.size() + starting;
    }

    void refreshGuis() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof QueueGui gui) gui.render(p);
        }
    }

    // ------------------------------------------------------------------ queueing

    void clickQueue(Player p, MatchType t) {
        UUID id = p.getUniqueId();
        if (isInMatch(p)) {
            p.sendMessage(Text.msg("<red>You are already in a match."));
            p.closeInventory();
            return;
        }
        if (queue.queueOf(id) == t) {
            leaveQueue(p);
            return;
        }
        int max = plugin.getConfig().getInt("max-concurrent-matches", 10);
        if (activeCount() >= max) {
            p.sendMessage(Text.msg("<red>All arenas are busy right now, try again in a moment."));
            return;
        }

        List<UUID> ready = queue.join(id, t);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        if (ready == null) {
            p.sendMessage(Text.msg("Joined the <white>" + t.label + "<gray> queue <dark_gray>(<white>"
                    + queue.size(t) + "<dark_gray>/<white>" + t.players + "<dark_gray>)"));
        } else {
            startMatch(t, ready);
        }
        refreshGuis();
    }

    void leaveQueue(Player p) {
        if (queue.leave(p.getUniqueId())) {
            p.sendMessage(Text.msg("You left the queue."));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        } else {
            p.sendMessage(Text.msg("<red>You are not in a queue."));
        }
        refreshGuis();
    }

    // ------------------------------------------------------------------ starting a match

    private void startMatch(MatchType type, List<UUID> ids) {
        starting++;
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(Text.msg("<green>Match found! <gray>Preparing the arena..."));
        }

        FileConfiguration cfg = plugin.getConfig();
        Location[] centerRef = new Location[1];

        findSpot(world, 0, 0, cfg.getDouble("spawn-range", 50000),
                cfg.getDouble("min-center-distance", 3000), reservedCenters, 0, 300)
                .thenComposeAsync(center -> {
                    centerRef[0] = center;
                    reservedCenters.add(center);
                    return pickSpots(center.getX(), center.getZ(),
                            cfg.getDouble("player-spawn-radius", 400),
                            cfg.getDouble("min-player-distance", 150), ids.size())
                            .thenAcceptAsync(spots -> launch(type, ids, center, spots), mainThread);
                }, mainThread)
                .whenCompleteAsync((v, ex) -> {
                    starting--;
                    if (ex != null) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to start a match", ex);
                        if (centerRef[0] != null) reservedCenters.remove(centerRef[0]);
                        for (UUID id : ids) {
                            Player p = Bukkit.getPlayer(id);
                            if (p != null) p.sendMessage(Text.msg("<red>Could not start the match. Please queue again."));
                        }
                    }
                    refreshGuis();
                }, mainThread);
    }

    private void launch(MatchType type, List<UUID> ids, Location center, List<Location> spots) {
        List<Player> online = new ArrayList<>();
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && !byPlayer.containsKey(id)) online.add(p);
        }
        if (online.size() < 2) {
            reservedCenters.remove(center);
            for (Player p : online) p.sendMessage(Text.msg("<red>Not enough players were left, the match was cancelled."));
            return;
        }

        FileConfiguration cfg = plugin.getConfig();
        double radius = cfg.getDouble("arena-radius", 500);

        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(center.getX(), center.getZ());
        border.setSize(radius * 2);
        border.setWarningDistance(10);

        Match m = new Match(type, world, center, border, radius);
        m.totalGrind = cfg.getInt("grind-minutes", 30) * 60;
        m.secondsLeft = m.totalGrind;
        m.bar.name(Text.mm("<red><bold>Gather gear <gray>- <white>" + Text.time(m.secondsLeft)));

        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            UUID id = p.getUniqueId();
            Location spot = spots.get(i % spots.size());

            m.participants.put(id, new Match.Participant(id, p.getName(), spot));
            m.alive.add(id);
            byPlayer.put(id, m);

            // Never overwrite an older, still-unrestored snapshot: it holds the real inventory.
            if (!snapshots.has(id)) snapshots.save(p);
            prepareFresh(p);
            p.showBossBar(m.bar);
            p.teleportAsync(spot).thenAcceptAsync(ok -> {
                if (ok && byPlayer.get(id) == m) p.setWorldBorder(border);
            }, mainThread);

            p.showTitle(Title.title(Text.mm("<red><bold>" + type.label),
                    Text.mm("<gray>Gather gear for <white>" + (m.totalGrind / 60) + " minutes"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600))));
            p.sendMessage(Text.msg("<yellow>Gear up! <gray>You have <white>" + (m.totalGrind / 60)
                    + " minutes<gray> before the final fight in the center of the arena."));
        }

        matches.add(m);
        m.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(m), 20L, 20L);
    }

    // ------------------------------------------------------------------ match loop

    private void tick(Match m) {
        switch (m.state) {
            case GRIND -> tickGrind(m);
            case COUNTDOWN -> tickCountdown(m);
            case FIGHT -> tickFight(m);
            default -> { }
        }
    }

    private void tickGrind(Match m) {
        m.secondsLeft--;
        updateBar(m, "<red><bold>Gather gear <gray>- <white>" + Text.time(m.secondsLeft),
                m.secondsLeft / (float) m.totalGrind, BossBar.Color.GREEN);

        for (int mark : GRIND_ANNOUNCE) {
            if (m.secondsLeft == mark) {
                m.message(Text.msg("<yellow>" + Text.time(mark) + "<gray> until the final fight!"));
                m.sound(Sound.BLOCK_NOTE_BLOCK_PLING, 1f);
            }
        }
        if (m.secondsLeft <= 0) startTransition(m);
    }

    private void startTransition(Match m) {
        m.state = Match.State.TRANSITION;
        updateBar(m, "<gold><bold>Teleporting to the center...", 1f, BossBar.Color.YELLOW);
        m.message(Text.msg("<gold>Time is up! <gray>Teleporting everyone to the center..."));

        FileConfiguration cfg = plugin.getConfig();
        pickSpots(m.center.getX(), m.center.getZ(), cfg.getDouble("fight-spawn-radius", 15), 8,
                Math.max(1, m.alive.size()))
                .whenCompleteAsync((spots, ex) -> {
                    if (ex != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not pick fight spots, using the center", ex);
                        spots = List.of(m.center);
                    }
                    fightTeleport(m, spots);
                }, mainThread);
    }

    private void fightTeleport(Match m, List<Location> spots) {
        if (m.state != Match.State.TRANSITION) return;
        m.fightCenter = m.center;

        List<CompletableFuture<Boolean>> tps = new ArrayList<>();
        int i = 0;
        for (Player p : m.onlineAlive()) {
            heal(p);
            tps.add(p.teleportAsync(spots.get(i++ % spots.size())));
        }
        CompletableFuture.allOf(tps.toArray(new CompletableFuture[0]))
                .whenCompleteAsync((v, ex) -> beginCountdown(m), mainThread);
    }

    private void beginCountdown(Match m) {
        if (m.state != Match.State.TRANSITION) return;
        m.state = Match.State.COUNTDOWN;
        m.secondsLeft = plugin.getConfig().getInt("fight-countdown-seconds", 10);
        m.message(Text.msg("<red>The final fight begins soon - get ready!"));
    }

    private void tickCountdown(Match m) {
        if (m.secondsLeft <= 0) {
            beginFight(m);
            return;
        }
        updateBar(m, "<red><bold>Fight starts in <white>" + m.secondsLeft, 1f, BossBar.Color.YELLOW);
        for (Player p : m.onlineParticipants()) {
            p.showTitle(Title.title(Text.mm("<red><bold>" + m.secondsLeft), Text.mm("<gray>Get ready"),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
        }
        m.sound(Sound.UI_BUTTON_CLICK, 1f);
        m.secondsLeft--;
    }

    private void beginFight(Match m) {
        m.state = Match.State.FIGHT;
        m.fightSeconds = 0;
        updateBar(m, "<red><bold>Fight to the death!", 1f, BossBar.Color.RED);
        for (Player p : m.onlineParticipants()) {
            p.showTitle(Title.title(Text.mm("<dark_red><bold>FIGHT!"), Text.mm("<gray>Last one standing wins"),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));
        }
        m.sound(Sound.ENTITY_ENDER_DRAGON_GROWL, 1f);
    }

    private void tickFight(Match m) {
        m.fightSeconds++;
        FileConfiguration cfg = plugin.getConfig();
        float progress = 1f;
        String label = "<red><bold>Fight to the death!";

        if (cfg.getBoolean("fight-shrink.enabled", true)) {
            double start = m.radius * 2;
            double end = Math.min(start, cfg.getDouble("fight-shrink.final-size", 50));
            int dur = Math.max(1, cfg.getInt("fight-shrink.duration-seconds", 180));
            double t = Math.min(1.0, m.fightSeconds / (double) dur);
            double size = start + (end - start) * t;

            m.halfSize = size / 2;
            m.border.setSize(size);
            progress = (float) (1.0 - t);
            label = t >= 1.0
                    ? "<red><bold>Final zone - fight!"
                    : "<red><bold>Border shrinking <gray>- <white>" + Text.time(dur - m.fightSeconds);

            double dmg = cfg.getDouble("fight-shrink.outside-damage", 4.0);
            for (Player p : m.onlineParticipants()) {
                p.setWorldBorder(m.border);
                if (m.state == Match.State.FIGHT && m.alive.contains(p.getUniqueId())
                        && m.cheb(p.getX(), p.getZ()) > m.halfSize + 0.5) {
                    p.damage(dmg);
                    p.sendActionBar(Text.mm("<red>Get back inside the border!"));
                }
            }
        }
        updateBar(m, label, progress, BossBar.Color.RED);
    }

    private void updateBar(Match m, String name, float progress, BossBar.Color color) {
        m.bar.name(Text.mm(name));
        m.bar.progress(Math.max(0f, Math.min(1f, progress)));
        m.bar.color(color);
    }

    // ------------------------------------------------------------------ eliminations / ending

    /** Called by the listener when a player dies in the final fight. */
    void eliminate(Match m, UUID id) {
        if (!m.alive.remove(id)) return;
        if (m.alive.size() > 1) {
            m.message(Text.msg("<red>" + m.alive.size() + " players remain."));
        }
        checkEnd(m);
    }

    void checkEnd(Match m) {
        if (m.state == Match.State.ENDING || m.alive.size() > 1) return;
        UUID winner = m.alive.stream().findFirst().orElse(null);
        endMatch(m, winner);
    }

    private void endMatch(Match m, UUID winner) {
        m.state = Match.State.ENDING;
        if (m.task != null) m.task.cancel();

        String name = winner != null && m.participants.containsKey(winner) ? m.participants.get(winner).name() : null;
        for (Player p : m.onlineParticipants()) {
            p.showTitle(Title.title(
                    Text.mm(name != null ? "<gold><bold>" + name + " wins!" : "<gray><bold>Draw"),
                    Text.mm("<gray>You will be sent back shortly"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        updateBar(m, name != null ? "<gold><bold>" + name + " wins!" : "<gray><bold>Draw", 1f, BossBar.Color.YELLOW);
        if (name != null) {
            Bukkit.broadcast(Text.msg("<white>" + name + " <gray>won a <white>" + m.type.label + "<gray> match!"));
        }

        int delay = Math.max(1, plugin.getConfig().getInt("end-delay-seconds", 10));
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(m, false), delay * 20L);
    }

    private void finish(Match m, boolean syncTeleport) {
        if (!matches.remove(m)) return;
        if (m.task != null) m.task.cancel();
        reservedCenters.remove(m.center);

        for (Match.Participant part : new ArrayList<>(m.participants.values())) {
            byPlayer.remove(part.id(), m);
            Player p = Bukkit.getPlayer(part.id());
            if (p != null && p.isOnline()) leaveArena(p, m, syncTeleport);
        }
        m.participants.clear();
        m.alive.clear();
        refreshGuis();
    }

    private void leaveArena(Player p, Match m, boolean syncTeleport) {
        p.setWorldBorder(null);
        p.hideBossBar(m.bar);
        if (p.isDead()) p.spigot().respawn();
        if (!snapshots.restore(p, syncTeleport)) {
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
    }

    /** /pvp stop - ends every running match and sends everybody home. */
    void stopAll() {
        for (Match m : new ArrayList<>(matches)) {
            m.state = Match.State.ENDING;
            finish(m, false);
        }
        queue.clear();
        refreshGuis();
    }

    /** Plugin disable: put everyone back synchronously. */
    void shutdown() {
        for (Match m : new ArrayList<>(matches)) {
            m.state = Match.State.ENDING;
            finish(m, true);
        }
        queue.clear();
    }

    // ------------------------------------------------------------------ player lifecycle

    void onQuit(Player p) {
        if (queue.leave(p.getUniqueId())) refreshGuis();
        Match m = byPlayer.remove(p.getUniqueId());
        if (m == null) return;

        m.participants.remove(p.getUniqueId());
        m.alive.remove(p.getUniqueId());
        p.setWorldBorder(null);
        m.message(Text.msg("<red>" + p.getName() + " left the match."));
        checkEnd(m);
        // The snapshot stays on disk; it is restored when the player joins again.
    }

    void onJoin(Player p) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || byPlayer.containsKey(id) || !snapshots.has(id)) return;
            p.setWorldBorder(null);
            snapshots.restore(p, true);
            p.sendMessage(Text.msg("Your inventory and position from before the match were restored."));
        }, 10L);
    }

    // ------------------------------------------------------------------ player helpers

    private void prepareFresh(Player p) {
        p.closeInventory();
        p.leaveVehicle();
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setGameMode(GameMode.SURVIVAL);
        heal(p);
        p.setSaturation(5f);
        p.setExhaustion(0f);
        p.setLevel(0);
        p.setExp(0f);
    }

    private void heal(Player p) {
        AttributeInstance maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(maxAttr != null ? maxAttr.getValue() : 20.0);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    // ------------------------------------------------------------------ finding surface spots

    private CompletableFuture<List<Location>> pickSpots(double cx, double cz, double radius, double spread, int n) {
        List<Location> taken = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < n; i++) {
            chain = chain.thenComposeAsync(
                    v -> findSpot(world, cx, cz, radius, spread, taken, 0, 200).thenAccept(taken::add),
                    mainThread);
        }
        return chain.thenApply(v -> taken);
    }

    /**
     * Picks a random point in a disc and returns the surface location above it. Rejects water, lava,
     * trees and other unsafe ground, and keeps {@code spread} blocks away from the locations in
     * {@code taken} (the spread requirement relaxes as attempts run out so this always finishes).
     */
    private CompletableFuture<Location> findSpot(World w, double cx, double cz, double radius, double spread,
                                                 List<Location> taken, int attempt, int maxAttempts) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(rnd.nextDouble()) * radius;
        int x = (int) Math.floor(cx + Math.cos(angle) * dist);
        int z = (int) Math.floor(cz + Math.sin(angle) * dist);

        double minSpread = spread * (1.0 - (double) attempt / maxAttempts);
        for (Location t : taken) {
            if (Math.hypot(t.getX() - x, t.getZ() - z) < minSpread) {
                return findSpot(w, cx, cz, radius, spread, taken, attempt + 1, maxAttempts);
            }
        }

        return w.getChunkAtAsync(x >> 4, z >> 4, true).thenComposeAsync(chunk -> {
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block ground = w.getBlockAt(x, y, z);
            Location loc = new Location(w, x + 0.5, y + 1, z + 0.5);
            if (isSafe(ground) || attempt + 1 >= maxAttempts) return CompletableFuture.completedFuture(loc);
            return findSpot(w, cx, cz, radius, spread, taken, attempt + 1, maxAttempts);
        }, mainThread);
    }

    private static boolean isSafe(Block ground) {
        Material t = ground.getType();
        if (ground.isLiquid() || t == Material.POWDER_SNOW || t == Material.MAGMA_BLOCK || t == Material.CACTUS
                || t == Material.CAMPFIRE || t == Material.SOUL_CAMPFIRE || t == Material.SWEET_BERRY_BUSH
                || t == Material.FIRE || t == Material.SOUL_FIRE) return false;
        if (ground.getY() <= ground.getWorld().getMinHeight()) return false;
        Block a = ground.getRelative(BlockFace.UP);
        Block b = a.getRelative(BlockFace.UP);
        return a.isPassable() && !a.isLiquid() && b.isPassable() && !b.isLiquid();
    }
}
