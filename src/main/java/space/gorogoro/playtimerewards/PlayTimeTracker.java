package space.gorogoro.playtimerewards;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイ時間の計上と離席の判定。
 * 毎秒は数えず、ログイン・離席・戻り・ログアウトなどの時刻の差で計算する。
 * 操作のイベントでは「最後に操作した時刻」を記録するだけにし、離席の判定は 1 分ごとの tick() で行う。
 */
final class PlayTimeTracker implements Listener {

    private final PlayTimeRewards plugin;
    private final PlayerDataStore store;
    private final Map<UUID, Session> sessions = new HashMap<>();

    PlayTimeTracker(PlayTimeRewards plugin, PlayerDataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    Session session(UUID uuid) {
        return sessions.get(uuid);
    }

    // ---- ログイン・ログアウト ----

    /** ログインした人、またはプラグインの起動時にすでにログインしている人の記録を始める */
    void start(Player player, long now, boolean joined) {
        checkRoll(now);
        PlayerRecord r = store.getOrCreate(player.getUniqueId(), player.getName());
        if (r.firstPlayed <= 0L) {
            long first = player.getFirstPlayed();
            if (first > 0L) {
                r.firstPlayed = first;
            }
        }
        if (joined) {
            r.lastLogin = now;
        }
        countLoginDay(r, store.today());
        sessions.put(player.getUniqueId(), new Session(now));
    }

    void end(Player player, long now) {
        checkRoll(now);
        Session s = sessions.remove(player.getUniqueId());
        PlayerRecord r = store.get(player.getUniqueId());
        if (s != null && r != null) {
            flush(r, s, now);
        }
    }

    /** 全員分の記録を止める(プラグインの停止時) */
    void endAll(long now) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            end(p, now);
        }
        sessions.clear();
    }

    private void countLoginDay(PlayerRecord r, LocalDate date) {
        long day = date.toEpochDay();
        if (r.lastLoginDay != day) {
            r.lastLoginDay = day;
            r.loginDays++;
        }
    }

    // ---- 計上 ----

    /** [from, to) のプレイ時間を記録に足す。from > to のときは、その分を差し引く。 */
    private void addInterval(PlayerRecord r, Session s, long from, long to) {
        if (from == to) {
            return;
        }
        long sign = from < to ? 1L : -1L;
        long a = Math.min(from, to);
        long b = Math.max(from, to);
        r.total = Math.max(0L, r.total + sign * (b - a));
        r.today = Math.max(0L, r.today + sign * overlap(a, b, store.todayStartMillis()));
        r.week = Math.max(0L, r.week + sign * overlap(a, b, store.weekStartMillis()));
        r.month = Math.max(0L, r.month + sign * overlap(a, b, store.monthStartMillis()));
        s.activeMillis = Math.max(0L, s.activeMillis + sign * (b - a));
    }

    private static long overlap(long a, long b, long periodStart) {
        return Math.max(0L, b - Math.max(a, periodStart));
    }

    /** 離席していなければ、ここまでのプレイ時間を記録に足す */
    private void flush(PlayerRecord r, Session s, long now) {
        if (s.segmentStart >= 0L) {
            addInterval(r, s, s.segmentStart, now);
            s.segmentStart = now;
        }
    }

    /** 保存の前に、ログイン中の人のここまでのプレイ時間を記録に足す */
    void checkpointAll(long now) {
        checkRoll(now);
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            PlayerRecord r = store.get(e.getKey());
            if (r != null) {
                flush(r, e.getValue(), now);
            }
        }
    }

    /** 日付が変わっていれば、変わった時刻までのプレイ時間を足してから、今日・今週・今月を切り替える */
    void checkRoll(long now) {
        LocalDate date = LocalDate.now(PlayerDataStore.ZONE);
        if (date.equals(store.today())) {
            return;
        }
        long boundary = Math.min(now, date.atStartOfDay(PlayerDataStore.ZONE).toInstant().toEpochMilli());
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            PlayerRecord r = store.get(e.getKey());
            Session s = e.getValue();
            if (r != null && s.segmentStart >= 0L && s.segmentStart < boundary) {
                flush(r, s, boundary);
            }
        }
        store.rollTo(date);
        // 日をまたいでログインし続けている人も、ログイン日数を 1 日増やす
        for (UUID uuid : sessions.keySet()) {
            PlayerRecord r = store.get(uuid);
            if (r != null) {
                countLoginDay(r, date);
            }
        }
    }

    /** 1 分ごとに呼ぶ。操作が無いまま一定時間たった人を離席にする。 */
    void tick(long now, long afkMillis) {
        checkRoll(now);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Session s = sessions.get(p.getUniqueId());
            PlayerRecord r = store.get(p.getUniqueId());
            if (s == null || r == null || s.afk) {
                continue;
            }
            long last = s.lastActivity;
            if (now - last >= afkMillis) {
                // 最後に操作した時点までさかのぼって離席にする
                addInterval(r, s, s.segmentStart, last);
                s.segmentStart = -1L;
                s.afkStart = last;
                s.afk = true;
                Bukkit.broadcast(plugin.messages().get("afk.away", "player", p.getName()));
            }
        }
    }

    /** ログイン中の人の、まだ記録に足していない分も含めた合計時間 */
    long liveTotal(PlayerRecord r, long now) {
        Session s = sessions.get(r.uuid);
        return r.total + (s == null ? 0L : s.pending(now));
    }

    long livePending(PlayerRecord r, long now) {
        Session s = sessions.get(r.uuid);
        return s == null ? 0L : s.pending(now);
    }

    // ---- 操作 ----

    private void touch(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        long now = System.currentTimeMillis();
        s.lastActivity = now;
        if (s.afk) {
            back(player, s, now);
        }
    }

    /** 離席から戻った */
    private void back(Player player, Session s, long now) {
        s.afk = false;
        s.afkMillis += Math.max(0L, now - s.afkStart);
        s.segmentStart = now;
        Bukkit.broadcast(plugin.messages().get("afk.back", "player", player.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        long now = System.currentTimeMillis();
        start(event.getPlayer(), now, true);
        plugin.promotions().check(event.getPlayer(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        // 乗り物での移動は数えない(視点を動かしたときだけ数える)
        if (event.getPlayer().isInsideVehicle()) {
            if (!event.hasChangedOrientation()) {
                return;
            }
        } else if (!event.hasChangedPosition() && !event.hasChangedOrientation()) {
            return;
        }
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        // チャットはメインスレッド以外で発生するため、時刻だけ記録し、戻りの処理はメインスレッドで行う
        Player player = event.getPlayer();
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        s.lastActivity = System.currentTimeMillis();
        if (s.afk) {
            Bukkit.getScheduler().runTask(plugin, () -> touch(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            touch(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            touch(player);
        }
    }
}
