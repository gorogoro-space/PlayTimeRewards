package space.gorogoro.playtimerewards;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーデータ(players.yml)。メモリ上のデータはメインスレッドだけで扱う。
 * ファイルへの書き出しは snapshot() で写しを取り、別スレッドで write() する。
 */
final class PlayerDataStore {

    static final ZoneId ZONE = ZoneId.systemDefault();

    private final Map<UUID, PlayerRecord> records = new HashMap<>();
    /** 小文字の名前 → UUID */
    private final Map<String, UUID> nameIndex = new HashMap<>();

    private LocalDate today;
    private LocalDate weekStart;
    private LocalDate monthStart;
    private long todayStartMillis;
    private long weekStartMillis;
    private long monthStartMillis;

    PlayerDataStore() {
        setPeriods(LocalDate.now(ZONE));
    }

    /**
     * players.yml を読み込む。ファイルが無ければ空のまま。
     * 壊れていて読めないときは例外を投げる(上書きしないように、呼び出し側でプラグインを止める)。
     */
    void load(File file) throws IOException, InvalidConfigurationException {
        records.clear();
        nameIndex.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);

        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                ConfigurationSection s = players.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                PlayerRecord r = new PlayerRecord(uuid, s.getString("name"));
                r.firstPlayed = s.getLong("first-played");
                r.total = s.getLong("total");
                r.today = s.getLong("today");
                r.week = s.getLong("week");
                r.month = s.getLong("month");
                r.lastLogin = s.getLong("last-login");
                r.loginDays = s.getInt("login-days");
                String day = s.getString("last-login-day");
                if (day != null) {
                    r.lastLoginDay = LocalDate.parse(day).toEpochDay();
                }
                r.votes = s.getInt("votes");
                records.put(uuid, r);
            }
        }
        for (PlayerRecord r : records.values()) {
            indexName(r);
        }

        // 保存されている期間の開始日を戻し、今の期間とずれていればその場で切り替える
        String t = yaml.getString("periods.today");
        String w = yaml.getString("periods.week");
        String m = yaml.getString("periods.month");
        if (t != null && w != null && m != null) {
            today = LocalDate.parse(t);
            weekStart = LocalDate.parse(w);
            monthStart = LocalDate.parse(m);
        }
        rollTo(LocalDate.now(ZONE));
    }

    PlayerRecord get(UUID uuid) {
        return records.get(uuid);
    }

    /** 記録が無ければ作る。名前が変わっていれば更新する。 */
    PlayerRecord getOrCreate(UUID uuid, String name) {
        PlayerRecord r = records.get(uuid);
        if (r == null) {
            r = new PlayerRecord(uuid, name);
            records.put(uuid, r);
            nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
        } else if (!name.equals(r.name)) {
            if (r.name != null) {
                nameIndex.remove(r.name.toLowerCase(Locale.ROOT), uuid);
            }
            r.name = name;
            nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
        }
        return r;
    }

    /** 名前から記録を探す(大文字・小文字は区別しない)。無ければ null。 */
    PlayerRecord findByName(String name) {
        UUID uuid = nameIndex.get(name.toLowerCase(Locale.ROOT));
        return uuid == null ? null : records.get(uuid);
    }

    Collection<PlayerRecord> all() {
        return records.values();
    }

    /** 同じ名前が複数あるときは、最後にログインした人を優先する */
    private void indexName(PlayerRecord r) {
        if (r.name == null) {
            return;
        }
        String key = r.name.toLowerCase(Locale.ROOT);
        UUID current = nameIndex.get(key);
        if (current != null) {
            PlayerRecord other = records.get(current);
            if (other != null && other.lastLogin >= r.lastLogin) {
                return;
            }
        }
        nameIndex.put(key, r.uuid);
    }

    LocalDate today() {
        return today;
    }

    long todayStartMillis() {
        return todayStartMillis;
    }

    long weekStartMillis() {
        return weekStartMillis;
    }

    long monthStartMillis() {
        return monthStartMillis;
    }

    /**
     * 日付が変わっていれば、今日・今週・今月の時間を 0 に戻す。
     * 切り替える前に、ログイン中の人のプレイ時間を記録に足しておくこと。
     */
    void rollTo(LocalDate date) {
        LocalDate newWeek = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate newMonth = date.withDayOfMonth(1);
        boolean day = !date.equals(today);
        boolean week = !newWeek.equals(weekStart);
        boolean month = !newMonth.equals(monthStart);
        if (day || week || month) {
            for (PlayerRecord r : records.values()) {
                if (day) {
                    r.today = 0L;
                }
                if (week) {
                    r.week = 0L;
                }
                if (month) {
                    r.month = 0L;
                }
            }
        }
        setPeriods(date);
    }

    private void setPeriods(LocalDate date) {
        today = date;
        weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        monthStart = date.withDayOfMonth(1);
        todayStartMillis = today.atStartOfDay(ZONE).toInstant().toEpochMilli();
        weekStartMillis = weekStart.atStartOfDay(ZONE).toInstant().toEpochMilli();
        monthStartMillis = monthStart.atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    /** 保存用の写しを取る(メインスレッドで呼ぶ) */
    Snapshot snapshot() {
        List<PlayerRecord> copies = new ArrayList<>(records.size());
        for (PlayerRecord r : records.values()) {
            copies.add(r.copy());
        }
        return new Snapshot(copies, today, weekStart, monthStart);
    }

    record Snapshot(List<PlayerRecord> records, LocalDate today, LocalDate weekStart, LocalDate monthStart) {
    }

    record TopEntry(String name, long total) {
    }

    /** 合計時間の上位を作る(別スレッドで呼んでよい) */
    static List<TopEntry> top(Snapshot snapshot, int limit) {
        return snapshot.records().stream()
                .filter(r -> r.name != null && r.total > 0L)
                .sorted(Comparator.comparingLong((PlayerRecord r) -> r.total).reversed())
                .limit(limit)
                .map(r -> new TopEntry(r.name, r.total))
                .toList();
    }

    /**
     * 写しをファイルに書き出す(別スレッドで呼ぶ)。
     * 一時ファイルに書いてから置き換え、前のファイルは .bak として 1 つ残す。
     */
    static void write(File file, Snapshot snapshot) throws IOException {
        StringBuilder sb = new StringBuilder(snapshot.records().size() * 260);
        sb.append("# PlayTimeRewards のプレイヤーデータ。サーバーの稼働中は編集しないでください。\n");
        sb.append("periods:\n");
        sb.append("  today: '").append(snapshot.today()).append("'\n");
        sb.append("  week: '").append(snapshot.weekStart()).append("'\n");
        sb.append("  month: '").append(snapshot.monthStart()).append("'\n");
        sb.append("players:\n");
        for (PlayerRecord r : snapshot.records()) {
            sb.append("  ").append(r.uuid).append(":\n");
            sb.append("    name: ").append(quote(r.name)).append('\n');
            sb.append("    first-played: ").append(r.firstPlayed).append('\n');
            sb.append("    total: ").append(r.total).append('\n');
            sb.append("    today: ").append(r.today).append('\n');
            sb.append("    week: ").append(r.week).append('\n');
            sb.append("    month: ").append(r.month).append('\n');
            sb.append("    last-login: ").append(r.lastLogin).append('\n');
            sb.append("    login-days: ").append(r.loginDays).append('\n');
            if (r.lastLoginDay != PlayerRecord.NO_DAY) {
                sb.append("    last-login-day: '").append(LocalDate.ofEpochDay(r.lastLoginDay)).append("'\n");
            }
            sb.append("    votes: ").append(r.votes).append('\n');
        }

        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(file.getName() + ".tmp");
        Path bak = target.resolveSibling(file.getName() + ".bak");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        if (Files.exists(target)) {
            Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String quote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "''") + "'";
    }
}
