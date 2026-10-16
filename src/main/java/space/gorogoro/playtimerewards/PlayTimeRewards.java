package space.gorogoro.playtimerewards;

import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * メインクラス。放置を除いたプレイ時間を記録して表示し、プレイ時間に応じて LuckPerms のグループを変える。
 */
public final class PlayTimeRewards extends JavaPlugin {

    /** ランキングに出す人数 */
    private static final int TOP_SIZE = 10;
    /** 離席と昇格を判定する間隔(1 分) */
    private static final long TICK_INTERVAL = 20L * 60L;

    record TopList(List<PlayerDataStore.TopEntry> entries, long computedAt) {
    }

    private Settings settings;
    private Messages messages;
    private PlayerDataStore store;
    private PlayTimeTracker tracker;
    private PromotionManager promotions;
    private File dataFile;
    /** 保存とランキングの集計を行う専用スレッド */
    private ExecutorService ioExecutor;
    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private volatile TopList top;
    /** データを読み込めたときだけ true。読み込みに失敗したときは、壊れたファイルを上書きしないように保存しない。 */
    private boolean loaded;

    @Override
    public void onEnable() {
        if (!loadSettings()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RegisteredServiceProvider<LuckPerms> lp = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (lp == null) {
            getLogger().severe("LuckPerms が見つからないため、停止します");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        store = new PlayerDataStore();
        dataFile = new File(getDataFolder(), "players.yml");
        try {
            store.load(dataFile);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "players.yml を読み込めないため、停止します。"
                    + "ファイルを直すか、players.yml.bak から戻してください", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loaded = true;

        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PlayTimeRewards-IO");
            t.setDaemon(true);
            return t;
        });
        tracker = new PlayTimeTracker(this, store);
        promotions = new PromotionManager(this, lp.getProvider());

        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            tracker.start(p, now, false);
        }
        getServer().getPluginManager().registerEvents(tracker, this);
        VoteListener.register(this);

        PluginCommand command = getCommand("playtime");
        if (command != null) {
            PlayTimeCommand executor = new PlayTimeCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(this, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        scheduleSave();

        // ランキングを最初に一度作っておく
        PlayerDataStore.Snapshot snapshot = store.snapshot();
        ioExecutor.execute(() -> top = new TopList(PlayerDataStore.top(snapshot, TOP_SIZE), System.currentTimeMillis()));
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdown();
            try {
                if (!ioExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    getLogger().warning("保存の完了を待ちきれませんでした");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!loaded) {
            return;
        }
        // 停止時だけは、データを失わないようにメインスレッドで保存する
        tracker.endAll(System.currentTimeMillis());
        try {
            PlayerDataStore.write(dataFile, store.snapshot());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "players.yml を保存できませんでした", e);
        }
    }

    private boolean loadSettings() {
        try {
            saveDefaultConfig();
            if (!new File(getDataFolder(), "messages.yml").exists()) {
                saveResource("messages.yml", false);
            }
            reloadConfig();
            settings = Settings.parse(getConfig(), getLogger());
            messages = Messages.load(new File(getDataFolder(), "messages.yml"), getResource("messages.yml"));
            return true;
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "設定を読み込めませんでした", e);
            return false;
        }
    }

    /** /playtime reload。config.yml と messages.yml を読み直す(プレイヤーデータは読み直さない)。 */
    boolean reload() {
        Settings oldSettings = settings;
        Messages oldMessages = messages;
        if (!loadSettings()) {
            settings = oldSettings;
            messages = oldMessages;
            return false;
        }
        if (oldSettings.saveIntervalMinutes != settings.saveIntervalMinutes) {
            scheduleSave();
        }
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        tracker.tick(now, settings.afkMillis);
        promotions.checkAll(now);
    }

    private void scheduleSave() {
        if (saveTask != null) {
            saveTask.cancel();
        }
        long interval = 20L * 60L * settings.saveIntervalMinutes;
        saveTask = Bukkit.getScheduler().runTaskTimer(this, this::saveAsync, interval, interval);
    }

    /** メインスレッドでは写しを取るだけにし、書き出しとランキングの集計は専用スレッドで行う */
    private void saveAsync() {
        tracker.checkpointAll(System.currentTimeMillis());
        PlayerDataStore.Snapshot snapshot = store.snapshot();
        ioExecutor.execute(() -> {
            try {
                PlayerDataStore.write(dataFile, snapshot);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "players.yml を保存できませんでした", e);
            }
            top = new TopList(PlayerDataStore.top(snapshot, TOP_SIZE), System.currentTimeMillis());
        });
    }

    Settings settings() {
        return settings;
    }

    Messages messages() {
        return messages;
    }

    PlayerDataStore store() {
        return store;
    }

    PlayTimeTracker tracker() {
        return tracker;
    }

    PromotionManager promotions() {
        return promotions;
    }

    TopList top() {
        return top;
    }
}
