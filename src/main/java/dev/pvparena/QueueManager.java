package dev.pvparena;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class QueueManager {
    private final Map<MatchType, LinkedHashSet<UUID>> queues = new EnumMap<>(MatchType.class);
    private final Map<UUID, MatchType> byPlayer = new HashMap<>();

    QueueManager() {
        for (MatchType t : MatchType.values()) queues.put(t, new LinkedHashSet<>());
    }

    MatchType queueOf(UUID id) {
        return byPlayer.get(id);
    }

    int size(MatchType t) {
        return queues.get(t).size();
    }

    boolean leave(UUID id) {
        MatchType t = byPlayer.remove(id);
        if (t == null) return false;
        queues.get(t).remove(id);
        return true;
    }

    /** Adds the player to a queue. Returns the players of a full game (already removed from the queue), or null. */
    List<UUID> join(UUID id, MatchType t) {
        leave(id);
        LinkedHashSet<UUID> q = queues.get(t);
        q.add(id);
        byPlayer.put(id, t);
        if (q.size() < t.players) return null;

        List<UUID> ready = new ArrayList<>(new ArrayList<>(q).subList(0, t.players));
        for (UUID u : ready) {
            q.remove(u);
            byPlayer.remove(u);
        }
        return ready;
    }

    void clear() {
        queues.values().forEach(LinkedHashSet::clear);
        byPlayer.clear();
    }
}
