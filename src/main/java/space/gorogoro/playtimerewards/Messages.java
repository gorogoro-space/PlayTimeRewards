package space.gorogoro.playtimerewards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * messages.yml の文言。起動時と再読み込み時に一度だけ読み込む。
 * プラグインフォルダのファイルに無いキーは、jar 内の既定の文言を使う。
 */
final class Messages {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final YamlConfiguration yaml;
    private final DateTimeFormatter dateTimeFormat;
    private final DateTimeFormatter dateFormat;

    private Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
        this.dateTimeFormat = DateTimeFormatter.ofPattern(text("date-time-format")).withZone(PlayerDataStore.ZONE);
        this.dateFormat = DateTimeFormatter.ofPattern(text("date-format")).withZone(PlayerDataStore.ZONE);
    }

    static Messages load(File file, InputStream defaults) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (defaults != null) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        return new Messages(yaml);
    }

    /** 文言の文字列。キーが無ければキー名を返す。 */
    String text(String key) {
        String s = yaml.getString(key);
        return s == null ? key : s;
    }

    /** 文言を取り出し、{名前} を値に置き換えて色付きの Component にする */
    Component get(String key, String... replacements) {
        String s = text(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            s = s.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return SERIALIZER.deserialize(s);
    }

    String dateTime(long epochMillis) {
        return dateTimeFormat.format(Instant.ofEpochMilli(epochMillis));
    }

    String date(long epochMillis) {
        return dateFormat.format(Instant.ofEpochMilli(epochMillis));
    }

    /** 1 分未満は秒、それ以上は日・時間・分で表す(例: 194日13時間19分、1時間59分、41分、9秒) */
    String duration(long millis) {
        long sec = Math.max(0L, millis) / 1000L;
        if (sec < 60L) {
            return unit("duration.seconds", sec);
        }
        long days = sec / 86_400L;
        long hours = (sec % 86_400L) / 3_600L;
        long minutes = (sec % 3_600L) / 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0L) {
            sb.append(unit("duration.days", days)).append(unit("duration.hours", hours));
        } else if (hours > 0L) {
            sb.append(unit("duration.hours", hours));
        }
        sb.append(unit("duration.minutes", minutes));
        return sb.toString();
    }

    private String unit(String key, long n) {
        return text(key).replace("{n}", Long.toString(n));
    }
}
