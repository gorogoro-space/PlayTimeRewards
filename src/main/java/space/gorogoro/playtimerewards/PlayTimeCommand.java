package space.gorogoro.playtimerewards;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /playtime コマンド。
 * /playtime(自分)、/playtime &lt;プレイヤー名&gt;(他人)、/playtime top、/playtime reload
 */
final class PlayTimeCommand implements TabExecutor {

    static final String PERM_OTHERS = "playtimerewards.others";
    static final String PERM_RELOAD = "playtimerewards.reload";

    private final PlayTimeRewards plugin;

    PlayTimeCommand(PlayTimeRewards plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.messages().get("command.player-only"));
                return true;
            }
            showSelf(player);
            return true;
        }
        String arg = args[0];
        if (arg.equalsIgnoreCase("top")) {
            showTop(sender);
            return true;
        }
        if (arg.equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PERM_RELOAD)) {
                sender.sendMessage(plugin.messages().get("command.no-permission"));
                return true;
            }
            sender.sendMessage(plugin.messages().get(plugin.reload() ? "command.reloaded" : "command.reload-failed"));
            return true;
        }
        if (!sender.hasPermission(PERM_OTHERS)) {
            sender.sendMessage(plugin.messages().get("command.no-permission"));
            return true;
        }
        showOther(sender, arg);
        return true;
    }

    private void showSelf(Player player) {
        long now = System.currentTimeMillis();
        PlayTimeTracker tracker = plugin.tracker();
        tracker.checkRoll(now);
        PlayerRecord r = plugin.store().get(player.getUniqueId());
        Session s = tracker.session(player.getUniqueId());
        if (r == null || s == null) {
            return;
        }
        Messages m = plugin.messages();
        long pending = tracker.livePending(r, now);
        String group = plugin.promotions().primaryGroup(player.getUniqueId());

        player.sendMessage(m.get("self.session", "time", m.duration(s.activeNow(now))));
        player.sendMessage(m.get("self.today", "time", m.duration(r.today + pending)));
        player.sendMessage(m.get("self.week", "time", m.duration(r.week + pending)));
        player.sendMessage(m.get("self.month", "time", m.duration(r.month + pending)));
        player.sendMessage(m.get("self.total", "time", m.duration(r.total + pending)));
        player.sendMessage(m.get("self.group", "group", group == null ? "-" : group));
        long remaining = plugin.promotions().remaining(group, r.total + pending);
        if (remaining > 0L) {
            player.sendMessage(m.get("self.until-promotion", "time", m.duration(remaining)));
        }
    }

    private void showOther(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        PlayerRecord found = online != null
                ? plugin.store().get(online.getUniqueId())
                : plugin.store().findByName(name);
        if (found == null) {
            sender.sendMessage(plugin.messages().get("command.not-found", "player", name));
            return;
        }
        UUID uuid = found.uuid;
        // グループはオフラインの人だと LuckPerms が別スレッドで読み込む
        CompletableFuture<String> group = plugin.promotions().loadPrimaryGroup(uuid)
                .exceptionally(ex -> null);
        // 初参加日時の記録が無い人は、別スレッドでその人の .dat を読む
        CompletableFuture<Long> firstPlayed;
        if (found.firstPlayed > 0L || Bukkit.getWorlds().isEmpty()) {
            firstPlayed = CompletableFuture.completedFuture(0L);
        } else {
            File dat = new File(new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata"), uuid + ".dat");
            firstPlayed = CompletableFuture.supplyAsync(() -> FirstPlayedReader.read(dat),
                    task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
        }
        // どちらも終わったら、メインスレッドで表示する
        group.thenCombine(firstPlayed, (g, first) -> {
            if (!plugin.isEnabled()) {
                return null;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                PlayerRecord r = plugin.store().get(uuid);
                if (r != null && r.firstPlayed <= 0L && first > 0L) {
                    r.firstPlayed = first;
                }
                sendOther(sender, uuid, g);
            });
            return null;
        });
    }

    private void sendOther(CommandSender sender, UUID uuid, String group) {
        PlayerRecord r = plugin.store().get(uuid);
        if (r == null) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayTimeTracker tracker = plugin.tracker();
        tracker.checkRoll(now);
        Messages m = plugin.messages();
        Session s = tracker.session(uuid);
        long pending = tracker.livePending(r, now);
        List<Component> lines = new ArrayList<>();

        lines.add(m.get("other.header", "player", r.name));
        if (r.firstPlayed > 0L) {
            // 日数は日付の差で数える(昨日の 23:59 なら 1 日前)
            long days = ChronoUnit.DAYS.between(
                    LocalDate.ofInstant(Instant.ofEpochMilli(r.firstPlayed), PlayerDataStore.ZONE),
                    LocalDate.now(PlayerDataStore.ZONE));
            lines.add(m.get("other.first-played-date", "time", m.dateTime(r.firstPlayed),
                    "days", Long.toString(days)));
        } else {
            lines.add(m.get("other.first-played-unknown"));
        }
        lines.add(m.get("other.login-days", "days", Integer.toString(r.loginDays)));
        lines.add(m.get("other.last-login", "time", r.lastLogin > 0L ? m.dateTime(r.lastLogin) : "-"));
        if (s != null) {
            lines.add(m.get("other.session", "time", m.duration(s.activeNow(now)),
                    "afk", s.afk ? m.text("other.session-afk") : ""));
            lines.add(m.get("other.afk-time", "time", m.duration(s.afkNow(now))));
        } else {
            lines.add(m.get("other.session-offline"));
            lines.add(m.get("other.afk-time-offline"));
        }
        lines.add(m.get("other.today", "time", m.duration(r.today + pending)));
        lines.add(m.get("other.week", "time", m.duration(r.week + pending)));
        lines.add(m.get("other.month", "time", m.duration(r.month + pending)));
        lines.add(m.get("other.total", "time", m.duration(r.total + pending)));
        lines.add(m.get("other.votes", "count", Integer.toString(r.votes)));
        lines.add(m.get("other.group", "group", group == null ? "-" : group));
        for (Component line : lines) {
            sender.sendMessage(line);
        }
    }

    private void showTop(CommandSender sender) {
        Messages m = plugin.messages();
        PlayTimeRewards.TopList top = plugin.top();
        if (top == null) {
            sender.sendMessage(m.get("top.preparing"));
            return;
        }
        sender.sendMessage(m.get("top.header"));
        sender.sendMessage(m.get("top.period", "date", m.date(top.computedAt())));
        if (top.entries().isEmpty()) {
            sender.sendMessage(m.get("top.empty"));
            return;
        }
        int rank = 1;
        for (PlayerDataStore.TopEntry e : top.entries()) {
            sender.sendMessage(m.get("top.line", "rank", Integer.toString(rank++),
                    "time", m.duration(e.total()), "player", e.name()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length != 1) {
            return result;
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        candidates.add("top");
        if (sender.hasPermission(PERM_RELOAD)) {
            candidates.add("reload");
        }
        if (sender.hasPermission(PERM_OTHERS)) {
            // 補完するのはログイン中の人の名前だけ
            for (Player p : Bukkit.getOnlinePlayers()) {
                candidates.add(p.getName());
            }
        }
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(c);
            }
        }
        return result;
    }
}
