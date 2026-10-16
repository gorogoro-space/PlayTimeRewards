package space.gorogoro.playtimerewards;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * config.yml の内容。起動時と再読み込み時に一度だけ解析する。
 */
final class Settings {

    record Promotion(String from, String to, double hours) {
        long millis() {
            return (long) (hours * 3_600_000L);
        }
    }

    final long afkMillis;
    final int saveIntervalMinutes;
    final List<Promotion> promotions;

    private Settings(long afkMillis, int saveIntervalMinutes, List<Promotion> promotions) {
        this.afkMillis = afkMillis;
        this.saveIntervalMinutes = saveIntervalMinutes;
        this.promotions = promotions;
    }

    static Settings parse(FileConfiguration config, Logger logger) {
        int afkMinutes = config.getInt("afk-minutes");
        if (afkMinutes < 1) {
            logger.warning("afk-minutes は 1 以上にしてください。10 として扱います。");
            afkMinutes = 10;
        }
        int saveInterval = config.getInt("save-interval-minutes");
        if (saveInterval < 1) {
            logger.warning("save-interval-minutes は 1 以上にしてください。5 として扱います。");
            saveInterval = 5;
        }

        List<Promotion> promotions = new ArrayList<>();
        for (Map<?, ?> map : config.getMapList("promotions")) {
            Object from = map.get("from");
            Object to = map.get("to");
            Object hours = map.get("hours");
            if (from == null || to == null || !(hours instanceof Number n) || n.doubleValue() <= 0) {
                logger.warning("promotions の設定に誤りがあるため無視します: " + map);
                continue;
            }
            promotions.add(new Promotion(from.toString(), to.toString(), n.doubleValue()));
        }
        return new Settings(afkMinutes * 60_000L, saveInterval, List.copyOf(promotions));
    }
}
