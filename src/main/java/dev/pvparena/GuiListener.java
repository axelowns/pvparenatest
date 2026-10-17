package dev.pvparena;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

final class GuiListener implements Listener {
    private final PvpArenaPlugin plugin;

    GuiListener(PvpArenaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof QueueGui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().getTopInventory().equals(e.getClickedInventory())) return;

        int slot = e.getSlot();
        MatchType[] types = MatchType.values();
        for (int i = 0; i < types.length; i++) {
            if (QueueGui.SLOTS[i] == slot) {
                plugin.matches().clickQueue(p, types[i]);
                return;
            }
        }
        if (slot == QueueGui.LEAVE_SLOT) plugin.matches().leaveQueue(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof QueueGui) e.setCancelled(true);
    }
}
