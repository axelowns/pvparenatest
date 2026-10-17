package dev.pvparena;

import org.bukkit.Material;

enum MatchType {
    DUEL(2, "1v1", Material.IRON_SWORD),
    TRIO(3, "1v1v1", Material.DIAMOND_SWORD),
    QUAD(4, "1v1v1v1", Material.NETHERITE_SWORD),
    QUINT(5, "1v1v1v1v1", Material.TRIDENT);

    final int players;
    final String label;
    final Material icon;

    MatchType(int players, String label, Material icon) {
        this.players = players;
        this.label = label;
        this.icon = icon;
    }
}
