package dev.pvparena;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

final class QueueGui implements InventoryHolder {
    static final int[] SLOTS = {10, 12, 14, 16};
    static final int LEAVE_SLOT = 22;

    private final PvpArenaPlugin plugin;
    private final Inventory inventory;

    QueueGui(PvpArenaPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, 27, Text.mm("<dark_red><bold>PvP Arena"));
        render(viewer);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void open(Player p) {
        p.openInventory(inventory);
    }

    void render(Player viewer) {
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);

        MatchManager mgr = plugin.matches();
        QueueManager q = mgr.queue();
        MatchType mine = q.queueOf(viewer.getUniqueId());
        MatchType[] types = MatchType.values();

        for (int i = 0; i < types.length; i++) {
            MatchType t = types[i];
            boolean queued = t == mine;
            ItemStack it = icon(t.icon,
                    (queued ? "<green><bold>" : "<yellow><bold>") + t.label,
                    "<gray>Free-for-all - last one standing wins.",
                    "<gray>In queue: <white>" + q.size(t) + "<dark_gray>/<white>" + t.players,
                    "",
                    queued ? "<red>Click to leave the queue" : "<green>Click to join the queue");
            if (queued) {
                ItemMeta meta = it.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                it.setItemMeta(meta);
            }
            inventory.setItem(SLOTS[i], it);
        }

        int grind = plugin.getConfig().getInt("grind-minutes", 30);
        inventory.setItem(4, icon(Material.CLOCK, "<aqua><bold>How it works",
                "<gray>1. Fill a game to start it.",
                "<gray>2. Gather gear for <white>" + grind + " minutes<gray>.",
                "<gray>3. Everyone meets in the center",
                "<gray>   and fights to the death.",
                "",
                "<gray>Matches running: <white>" + mgr.activeCount()));

        if (mine != null) {
            inventory.setItem(LEAVE_SLOT, icon(Material.BARRIER, "<red><bold>Leave queue"));
        }
    }

    private static ItemStack icon(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.mm(name));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) lines.add(Text.mm(l));
            meta.lore(lines);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }
}
