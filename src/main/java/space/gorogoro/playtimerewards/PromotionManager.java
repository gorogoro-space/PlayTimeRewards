package space.gorogoro.playtimerewards;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * プレイ時間による昇格(LuckPerms のグループの置き換え)。
 * 判定はメインスレッドでメモリ上のデータだけを見て行い、グループの書き換えは LuckPerms の API で別スレッドで行う。
 */
final class PromotionManager {

    private final PlayTimeRewards plugin;
    private final LuckPerms luckPerms;
    /** グループを書き換えている途中の人(二重に昇格させないため) */
    private final Set<UUID> pending = new HashSet<>();

    PromotionManager(PlayTimeRewards plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    /** ログイン中のプレイヤーのグループ(読み込まれていなければ null) */
    String primaryGroup(UUID uuid) {
        User user = luckPerms.getUserManager().getUser(uuid);
        return user == null ? null : user.getPrimaryGroup();
    }

    /** オフラインの人も含めてグループを取得する(必要なら LuckPerms が別スレッドで読み込む) */
    CompletableFuture<String> loadPrimaryGroup(UUID uuid) {
        User user = luckPerms.getUserManager().getUser(uuid);
        if (user != null) {
            return CompletableFuture.completedFuture(user.getPrimaryGroup());
        }
        return luckPerms.getUserManager().loadUser(uuid).thenApply(User::getPrimaryGroup);
    }

    /** 今のグループから次に昇格するまでの残り時間。昇格の対象でなければ -1 */
    long remaining(String group, long total) {
        if (group == null) {
            return -1L;
        }
        for (Settings.Promotion p : plugin.settings().promotions) {
            if (p.from().equalsIgnoreCase(group)) {
                return Math.max(0L, p.millis() - total);
            }
        }
        return -1L;
    }

    /** 昇格の条件を満たしていれば昇格させる(メインスレッドで呼ぶ) */
    void check(Player player, long now) {
        UUID uuid = player.getUniqueId();
        if (pending.contains(uuid)) {
            return;
        }
        PlayerRecord r = plugin.store().get(uuid);
        String group = primaryGroup(uuid);
        if (r == null || group == null) {
            return;
        }
        long total = plugin.tracker().liveTotal(r, now);
        for (Settings.Promotion p : plugin.settings().promotions) {
            if (!p.from().equalsIgnoreCase(group)) {
                continue;
            }
            if (total < p.millis()) {
                return;
            }
            promote(player, p);
            return;
        }
    }

    private void promote(Player player, Settings.Promotion p) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        pending.add(uuid);
        luckPerms.getUserManager().modifyUser(uuid, user -> {
            user.data().remove(InheritanceNode.builder(p.from()).build());
            user.data().add(InheritanceNode.builder(p.to()).build());
        }).whenComplete((v, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            pending.remove(uuid);
            if (ex != null) {
                plugin.getLogger().log(Level.WARNING, name + " の昇格に失敗しました", ex);
                return;
            }
            plugin.getLogger().info(name + " を " + p.from() + " から " + p.to() + " に昇格しました");
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                online.sendMessage(plugin.messages().get("promotion.self",
                        "hours", formatHours(p.hours()), "group", p.to()));
            }
            Bukkit.broadcast(plugin.messages().get("promotion.broadcast", "player", name, "group", p.to()));
        }));
    }

    private static String formatHours(double hours) {
        if (hours == Math.rint(hours)) {
            return Long.toString((long) hours);
        }
        return Double.toString(hours);
    }

    /** 1 分ごとに、ログイン中の全員を判定する */
    void checkAll(long now) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            check(p, now);
        }
    }
}
