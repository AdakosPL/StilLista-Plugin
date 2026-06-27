package pl.stillista.plugin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent queue of rewards owed to players who were OFFLINE when their vote
 * arrived. Backed by {@code pending.yml} in the plugin data folder so queued
 * rewards survive restarts. Names are stored lowercase; one list entry == one
 * pending reward cycle (so multiple votes while offline are all honoured).
 */
public final class PendingStore {

    private static final String KEY = "pending";

    private final File file;
    private final Logger logger;
    private final List<String> names = new ArrayList<String>();

    public PendingStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "pending.yml");
        this.logger = logger;
        load();
    }

    private synchronized void load() {
        names.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String n : cfg.getStringList(KEY)) {
            if (n != null && !n.isEmpty()) {
                names.add(n.toLowerCase());
            }
        }
    }

    private synchronized void save() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set(KEY, new ArrayList<String>(names));
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            cfg.save(file);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Nie udało się zapisać pending.yml", ex);
        }
    }

    /** Queue one pending reward cycle for the given (case-insensitive) name. */
    public synchronized void add(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        names.add(name.toLowerCase());
        save();
    }

    /**
     * Remove and count all pending reward cycles owed to the given name.
     * Returns how many reward cycles should be run for the player now.
     */
    public synchronized int claim(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        String lower = name.toLowerCase();
        int count = 0;
        for (int i = names.size() - 1; i >= 0; i--) {
            if (names.get(i).equals(lower)) {
                names.remove(i);
                count++;
            }
        }
        if (count > 0) {
            save();
        }
        return count;
    }

    public synchronized boolean has(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return names.contains(name.toLowerCase());
    }
}
