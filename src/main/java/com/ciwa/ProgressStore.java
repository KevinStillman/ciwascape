package com.ciwa;

import java.util.Objects;
import net.runelite.client.config.ConfigManager;

/** Tiny K/V store per quest using ConfigManager. Keys -> "Ciwascape.quest.<id>.<field>" */
public class ProgressStore
{
    private final ConfigManager cm;
    public ProgressStore(ConfigManager cm) { this.cm = Objects.requireNonNull(cm); }

    private String key(String questId, String field) {
        return "quest." + questId + "." + field;
    }

    public void put(String questId, String field, String value) {
        cm.setConfiguration("Ciwascape", key(questId, field), value);
    }

    public String get(String questId, String field, String def) {
        String v = cm.getConfiguration("Ciwascape", key(questId, field));
        return v != null ? v : def;
    }

    public void clearQuest(String questId) {
        // Optional: enumerate known fields in your quest and clear them.
        // For simple usage you can overwrite fields on reset instead.
    }
}
