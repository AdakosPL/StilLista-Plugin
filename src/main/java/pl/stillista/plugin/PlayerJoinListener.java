package pl.stillista.plugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Delivers rewards that were queued while the player was offline. On join we
 * drain the player's pending reward cycles from {@link PendingStore} and run
 * the configured reward commands once per queued vote.
 */
public final class PlayerJoinListener implements Listener {

    private final StilListaPlugin plugin;

    public PlayerJoinListener(StilListaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final PendingStore pending = plugin.getPending();
        if (pending == null || !pending.has(player.getName())) {
            return;
        }

        // Defer slightly so the player is fully loaded before commands run.
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                int cycles = pending.claim(player.getName());
                for (int i = 0; i < cycles; i++) {
                    plugin.runRewards(player.getName());
                }
                if (cycles > 0 && plugin.isClaimedMessageEnabled()
                        && !plugin.getClaimedMessageText().isEmpty()) {
                    player.sendMessage(
                            org.bukkit.ChatColor.translateAlternateColorCodes(
                                    '&', plugin.getClaimedMessageText()));
                }
            }
        }, 40L);
    }
}
