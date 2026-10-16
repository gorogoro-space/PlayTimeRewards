package space.gorogoro.playtimerewards;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * 初参加日時の読み込み。初回の起動時に一度だけ、別スレッドでワールドの playerdata/&lt;UUID&gt;.dat を読み、
 * 初参加日時をプレイヤーデータに入れる。ワールドのファイルは読むだけで書き込まない。
 * Bukkit の API は別スレッドで安全に呼べる保証が無いため、ファイルは自前で読む。
 */
final class FirstPlayedScanner {

    /** これだけ読むごとに、結果をメインスレッドで反映する */
    private static final int BATCH = 500;

    private final PlayTimeRewards plugin;
    private volatile boolean cancelled;
    private BukkitTask task;

    FirstPlayedScanner(PlayTimeRewards plugin) {
        this.plugin = plugin;
    }

    /** まだ読み終えていなければ読み込みを始める(メインスレッドで呼ぶ) */
    void startIfNeeded() {
        PlayerDataStore store = plugin.store();
        if (store.isFirstPlayedScanDone() || Bukkit.getWorlds().isEmpty()) {
            return;
        }
        List<UUID> targets = new ArrayList<>();
        for (PlayerRecord r : store.all()) {
            if (r.firstPlayed == PlayerRecord.FIRST_PLAYED_UNCHECKED) {
                targets.add(r.uuid);
            }
        }
        File dir = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata");
        plugin.getLogger().info("初参加日時の読み込みを始めます(" + targets.size() + " 人)");
        task = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> run(dir, targets));
    }

    void cancel() {
        cancelled = true;
        if (task != null) {
            task.cancel();
        }
    }

    private void run(File dir, List<UUID> targets) {
        Map<UUID, Long> results = new HashMap<>();
        for (UUID uuid : targets) {
            if (cancelled) {
                return;
            }
            results.put(uuid, read(new File(dir, uuid + ".dat")));
            if (results.size() >= BATCH) {
                apply(results, false);
                results = new HashMap<>();
            }
        }
        if (!cancelled) {
            apply(results, true);
        }
    }

    private void apply(Map<UUID, Long> results, boolean finished) {
        if (cancelled || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            PlayerDataStore store = plugin.store();
            for (Map.Entry<UUID, Long> e : results.entrySet()) {
                PlayerRecord r = store.get(e.getKey());
                // ログインして値が入った人は上書きしない
                if (r != null && r.firstPlayed == PlayerRecord.FIRST_PLAYED_UNCHECKED) {
                    r.firstPlayed = e.getValue();
                }
            }
            if (finished) {
                store.setFirstPlayedScanDone(true);
                plugin.getLogger().info("初参加日時の読み込みが終わりました");
            }
        });
    }

    /**
     * .dat から初参加日時を読む。ファイルが無いときや読めないときは「不明」。
     * 値が入っていないときは、Bukkit と同じくファイルの更新日時を使う。
     */
    static long read(File file) {
        if (!file.isFile()) {
            return PlayerRecord.FIRST_PLAYED_UNKNOWN;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readByte() != TAG_COMPOUND) {
                return PlayerRecord.FIRST_PLAYED_UNKNOWN;
            }
            in.readUTF();
            long value = findFirstPlayed(in);
            if (value > 0L) {
                return value;
            }
            long modified = file.lastModified();
            return modified > 0L ? modified : PlayerRecord.FIRST_PLAYED_UNKNOWN;
        } catch (IOException | RuntimeException e) {
            return PlayerRecord.FIRST_PLAYED_UNKNOWN;
        }
    }

    // ---- NBT(Minecraft のデータ形式)の読み取り ----

    private static final byte TAG_END = 0;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_COMPOUND = 10;

    /** 一番外側の compound から bukkit.firstPlayed を探す。無ければ 0。 */
    private static long findFirstPlayed(DataInputStream in) throws IOException {
        long result = 0L;
        while (true) {
            byte type = in.readByte();
            if (type == TAG_END) {
                return result;
            }
            String name = in.readUTF();
            if (type == TAG_COMPOUND && name.equals("bukkit")) {
                while (true) {
                    byte t = in.readByte();
                    if (t == TAG_END) {
                        break;
                    }
                    String n = in.readUTF();
                    if (t == TAG_LONG && n.equals("firstPlayed")) {
                        result = in.readLong();
                    } else {
                        skip(in, t);
                    }
                }
                if (result > 0L) {
                    return result;
                }
            } else {
                skip(in, type);
            }
        }
    }

    private static void skip(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1 -> skipBytes(in, 1);
            case 2 -> skipBytes(in, 2);
            case 3, 5 -> skipBytes(in, 4);
            case 4, 6 -> skipBytes(in, 8);
            case 7 -> skipBytes(in, in.readInt());
            case 8 -> skipBytes(in, in.readUnsignedShort());
            case 9 -> {
                byte elem = in.readByte();
                int len = in.readInt();
                for (int i = 0; i < len; i++) {
                    skip(in, elem);
                }
            }
            case 10 -> {
                while (true) {
                    byte t = in.readByte();
                    if (t == TAG_END) {
                        break;
                    }
                    skipBytes(in, in.readUnsignedShort());
                    skip(in, t);
                }
            }
            case 11 -> skipBytes(in, (long) in.readInt() * 4L);
            case 12 -> skipBytes(in, (long) in.readInt() * 8L);
            default -> throw new IOException("不明な NBT の型: " + type);
        }
    }

    private static void skipBytes(DataInputStream in, long n) throws IOException {
        if (n < 0L) {
            throw new EOFException();
        }
        in.skipNBytes(n);
    }
}
