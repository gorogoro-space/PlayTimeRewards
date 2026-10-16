package space.gorogoro.playtimerewards;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * ワールドの playerdata/&lt;UUID&gt;.dat から初参加日時を読む(読むだけで書き込まない)。
 * プラグインが止まっている間に参加した人など、初参加日時の記録が無い人のために使う。
 * 別スレッドで呼ぶ。Bukkit の API は別スレッドで安全に呼べる保証が無いため、ファイルは自前で読む。
 */
final class FirstPlayedReader {

    private FirstPlayedReader() {
    }

    /**
     * 初参加日時(エポックミリ秒)を返す。ファイルが無いときや読めないときは 0。
     * 値が入っていないときは、Bukkit と同じくファイルの更新日時を使う。
     */
    static long read(File file) {
        if (!file.isFile()) {
            return 0L;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readByte() != TAG_COMPOUND) {
                return 0L;
            }
            in.readUTF();
            long value = findFirstPlayed(in);
            if (value > 0L) {
                return value;
            }
            return Math.max(0L, file.lastModified());
        } catch (IOException | RuntimeException e) {
            return 0L;
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
