package space.gorogoro.playtimerewards;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * 投票の受け取り。Votifier(NuVotifier)の投票イベントを受けて、投票した人の投票数を 1 増やす。
 * Votifier が入っていなくても動くように、クラスを直接参照せずにイベントを登録する。
 */
final class VoteListener implements Listener {

    private final PlayTimeRewards plugin;
    private Method getVote;
    private Method getUsername;

    private VoteListener(PlayTimeRewards plugin) {
        this.plugin = plugin;
    }

    /** Votifier があれば投票イベントを登録する */
    static void register(PlayTimeRewards plugin) {
        Plugin votifier = Bukkit.getPluginManager().getPlugin("Votifier");
        if (votifier == null || !votifier.isEnabled()) {
            plugin.getLogger().info("Votifier が無いため、投票数は数えません");
            return;
        }
        try {
            ClassLoader loader = votifier.getClass().getClassLoader();
            Class<? extends Event> eventClass = Class.forName("com.vexsoftware.votifier.model.VotifierEvent", true, loader)
                    .asSubclass(Event.class);
            VoteListener listener = new VoteListener(plugin);
            listener.getVote = eventClass.getMethod("getVote");
            listener.getUsername = listener.getVote.getReturnType().getMethod("getUsername");
            Bukkit.getPluginManager().registerEvent(eventClass, listener, EventPriority.MONITOR,
                    (l, event) -> {
                        if (eventClass.isInstance(event)) {
                            listener.onVote(event);
                        }
                    }, plugin);
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().log(Level.WARNING, "Votifier の投票イベントを登録できませんでした", e);
        }
    }

    private void onVote(Event event) {
        String name;
        try {
            Object vote = getVote.invoke(event);
            name = vote == null ? null : (String) getUsername.invoke(vote);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "投票の内容を読み取れませんでした", e);
            return;
        }
        if (name == null || name.isEmpty()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            count(name);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> count(name));
        }
    }

    /** 記録にある名前なら投票数を 1 増やす。記録に無い名前は無視する。 */
    private void count(String name) {
        PlayerRecord r = plugin.store().findByName(name);
        if (r != null) {
            r.votes++;
        }
    }
}
