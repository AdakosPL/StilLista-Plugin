package pl.stillista.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * StilLista.pl vote-rewards integration (PULL/API model).
 *
 * <p>Periodically pulls unclaimed votes for this server from the StilLista API
 * using the per-server key, runs configured reward commands for the in-game
 * nickname (immediately if online, otherwise queued via {@link PendingStore}
 * until the player joins), then acknowledges the processed votes so they are
 * not handed out twice.</p>
 *
 * <p>Compiled against spigot-api 1.8.8 / Java 8 bytecode and uses only
 * long-stable Bukkit API + java.net, so it loads across Minecraft 1.8 .. 26.x.
 * (Caveat: only basic command dispatch / online-player lookup is guaranteed
 * across that whole range — version-specific APIs are intentionally avoided.)</p>
 */
public final class StilListaPlugin extends JavaPlugin {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private String apiUrl;
    private String serverKey;
    private long pollIntervalTicks;
    private boolean rewardOffline;
    private boolean claimedMessage;
    private String claimedMessageText;
    private List<String> rewards = new ArrayList<String>();

    private PendingStore pending;
    private BukkitTask pollTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pending = new PendingStore(getDataFolder(), getLogger());
        loadSettings();

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);

        if (serverKey == null || serverKey.isEmpty()) {
            getLogger().warning("==================================================");
            getLogger().warning(" Brak 'server-key' w config.yml — polling WYŁĄCZONY.");
            getLogger().warning(" Skopiuj klucz serwera z: " + apiUrl + "/dashboard/plugin");
            getLogger().warning(" i wklej go do plugins/StilLista/config.yml, potem: /stillista reload");
            getLogger().warning("==================================================");
            return;
        }

        // Validate the key on a background thread and log the bound server.
        getServer().getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                String ip = ping();
                if (ip != null) {
                    getLogger().info("Połączono z StilLista.pl — serwer: " + ip);
                } else {
                    getLogger().warning("Nie udało się zweryfikować klucza (ping). "
                            + "Sprawdź 'server-key' i 'api-url'. Spróbuję mimo to odpytywać API.");
                }
            }
        });

        startPolling();
        getLogger().info("StilLista włączona. Odpytywanie co " + (pollIntervalTicks / 20L) + "s.");
    }

    @Override
    public void onDisable() {
        stopPolling();
    }

    private void loadSettings() {
        reloadConfig();
        apiUrl = stripTrailingSlash(getConfig().getString("api-url", "https://stillista.pl"));
        serverKey = getConfig().getString("server-key", "").trim();
        long seconds = getConfig().getLong("poll-interval-seconds", 60L);
        if (seconds < 10L) {
            seconds = 10L;
        }
        pollIntervalTicks = seconds * 20L;
        rewardOffline = getConfig().getBoolean("reward-offline", true);
        claimedMessage = getConfig().getBoolean("claimed-message", true);
        claimedMessageText = getConfig().getString("claimed-message-text", "");
        rewards = getConfig().getStringList("rewards");
        if (rewards == null) {
            rewards = new ArrayList<String>();
        }
    }

    private void startPolling() {
        stopPolling();
        pollTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this, new Runnable() {
                    @Override
                    public void run() {
                        pollOnce();
                    }
                }, pollIntervalTicks, pollIntervalTicks);
    }

    private void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    /** Reload config + restart polling. Called from /stillista reload. */
    public void reloadAll() {
        loadSettings();
        stopPolling();
        if (serverKey != null && !serverKey.isEmpty()) {
            startPolling();
        }
    }

    // ----- Polling / processing --------------------------------------------

    /** Consecutive failed poll cycles — used to keep transient blips quiet. */
    private int pollFailStreak = 0;

    /** One poll cycle. Runs on an async scheduler thread. */
    private void pollOnce() {
        // One immediate retry handles a single transient timeout; persistent
        // failures are only warned about after a few cycles in a row so a flaky
        // network doesn't spam the console (it self-heals on the next poll).
        String body = null;
        Exception lastError = null;
        for (int attempt = 0; attempt < 2 && body == null; attempt++) {
            try {
                body = httpGet(apiUrl + "/api/plugin/queue?key=" + urlEncode(serverKey));
            } catch (Exception ex) {
                lastError = ex;
                if (attempt == 0) {
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        if (body == null) {
            pollFailStreak++;
            if (lastError != null && pollFailStreak == 3) {
                getLogger().warning("Brak połączenia z API StilLista ("
                        + lastError.getMessage() + "). Ponawiam co cykl…");
            }
            return;
        }

        if (pollFailStreak >= 3) {
            getLogger().info("Połączenie z API StilLista przywrócone.");
        }
        pollFailStreak = 0;

        final List<Vote> votes;
        try {
            votes = parseVotes(body);
        } catch (Exception ex) {
            getLogger().warning("Błąd parsowania kolejki głosów: " + ex.getMessage());
            return;
        }

        if (votes.isEmpty()) {
            return;
        }

        // Reward logic must touch the Bukkit API on the main thread.
        getServer().getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                final List<String> processedIds = new ArrayList<String>();
                for (Vote vote : votes) {
                    String name = vote.username;
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    Player online = findOnline(name);
                    if (online != null) {
                        runRewards(online.getName());
                        if (claimedMessage && !claimedMessageText.isEmpty()) {
                            online.sendMessage(color(claimedMessageText));
                        }
                        processedIds.add(vote.id);
                    } else if (rewardOffline) {
                        pending.add(name);
                        processedIds.add(vote.id);
                    }
                    // else: leave unclaimed so it's retried once the player is online.
                }

                if (!processedIds.isEmpty()) {
                    getServer().getScheduler().runTaskAsynchronously(
                            StilListaPlugin.this, new Runnable() {
                                @Override
                                public void run() {
                                    claim(processedIds);
                                }
                            });
                }
            }
        });
    }

    /** Run all configured reward commands for the given player name (main thread). */
    public void runRewards(String playerName) {
        for (String raw : rewards) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String cmd = color(raw.replace("%player%", playerName));
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception ex) {
                getLogger().warning("Nie udało się wykonać nagrody '" + cmd + "': " + ex.getMessage());
            }
        }
    }

    public PendingStore getPending() {
        return pending;
    }

    public boolean isClaimedMessageEnabled() {
        return claimedMessage;
    }

    public String getClaimedMessageText() {
        return claimedMessageText;
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    /** Case-insensitive online-player lookup (no version-specific API). */
    private static Player findOnline(String name) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    // ----- HTTP / parsing ---------------------------------------------------

    /** GET ?key validation. Returns the bound server ip, or null on failure. */
    private String ping() {
        try {
            String body = httpGet(apiUrl + "/api/plugin/ping?key=" + urlEncode(serverKey));
            if (body == null) {
                return null;
            }
            Object parsed = Json.parse(body);
            if (parsed instanceof Map) {
                Object server = ((Map<?, ?>) parsed).get("server");
                return server == null ? null : String.valueOf(server);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private void claim(List<String> ids) {
        try {
            String body = Json.claimBody(serverKey, ids);
            int code = httpPostJson(apiUrl + "/api/plugin/claim", body);
            if (code != 200) {
                getLogger().warning("Potwierdzenie głosów zwróciło kod " + code
                        + " — spróbuję ponownie w kolejnym cyklu.");
            }
        } catch (Exception ex) {
            getLogger().warning("Błąd potwierdzania głosów: " + ex.getMessage());
        }
    }

    private List<Vote> parseVotes(String body) {
        List<Vote> result = new ArrayList<Vote>();
        Object parsed = Json.parse(body);
        if (!(parsed instanceof Map)) {
            return result;
        }
        Object votesObj = ((Map<?, ?>) parsed).get("votes");
        if (!(votesObj instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) votesObj) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> obj = (Map<?, ?>) item;
            Object id = obj.get("id");
            Object username = obj.get("username");
            if (id == null || username == null) {
                continue;
            }
            String name = String.valueOf(username).trim();
            if (name.isEmpty()) {
                continue;
            }
            result.add(new Vote(String.valueOf(id), name));
        }
        return result;
    }

    private String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Api-Key", serverKey);
            conn.setRequestProperty("User-Agent", "StilLista-Plugin/1.0.0");
            int code = conn.getResponseCode();
            if (code == 401) {
                getLogger().warning("API odrzuciło klucz (401). Sprawdź 'server-key'.");
                return null;
            }
            if (code < 200 || code >= 300) {
                getLogger().warning("API zwróciło kod HTTP " + code + " dla GET " + urlStr);
                return null;
            }
            return readBody(conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private int httpPostJson(String urlStr, String jsonBody) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Api-Key", serverKey);
            conn.setRequestProperty("User-Agent", "StilLista-Plugin/1.0.0");
            byte[] payload = jsonBody.getBytes(UTF8);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                out.close();
            }
            int code = conn.getResponseCode();
            // Drain the stream so the connection can be reused / closed cleanly.
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is != null) {
                readBody(is);
            }
            return code;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, UTF8));
            }
        } finally {
            in.close();
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception ex) {
            return value;
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "https://stillista.pl";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    // ----- Commands ---------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("stillista")) {
            return false;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("stillista.admin")) {
                sender.sendMessage(color("&cBrak uprawnień."));
                return true;
            }
            reloadAll();
            if (serverKey == null || serverKey.isEmpty()) {
                sender.sendMessage(color("&eKonfiguracja przeładowana, ale &cbrak server-key&e — polling wyłączony."));
            } else {
                sender.sendMessage(color("&aKonfiguracja StilLista przeładowana. Odpytywanie co "
                        + (pollIntervalTicks / 20L) + "s."));
            }
            return true;
        }
        sender.sendMessage(color("&dStilLista &7v" + getDescription().getVersion()));
        sender.sendMessage(color("&7Użycie: &f/stillista reload"));
        return true;
    }

    /** Minimal value object for a pulled vote. */
    private static final class Vote {
        final String id;
        final String username;

        Vote(String id, String username) {
            this.id = id;
            this.username = username;
        }
    }
}
