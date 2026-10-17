package dev.pvparena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

final class Text {
    static final String PREFIX = "<dark_gray>[<red>PvP<dark_gray>] <gray>";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {}

    static Component mm(String s) {
        return MM.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    static Component msg(String s) {
        return mm(PREFIX + s);
    }

    static String time(int seconds) {
        seconds = Math.max(0, seconds);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
