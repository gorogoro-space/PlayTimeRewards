package space.gorogoro.playtimerewards;

import java.util.UUID;

/**
 * 1 人分の保存データ。メインスレッドだけで読み書きする(保存時は copy() した写しを別スレッドに渡す)。
 */
final class PlayerRecord {

    /** 初参加日時をまだ調べていない */
    static final long FIRST_PLAYED_UNCHECKED = 0L;
    /** 初参加日時を調べたが、わからなかった */
    static final long FIRST_PLAYED_UNKNOWN = -1L;
    /** 最後にログイン日数を数えた日が無い */
    static final long NO_DAY = Long.MIN_VALUE;

    final UUID uuid;
    String name;
    long firstPlayed = FIRST_PLAYED_UNCHECKED;
    /** 離席を除いたプレイ時間(ミリ秒) */
    long total;
    long today;
    long week;
    long month;
    /** 最終ログイン日時(エポックミリ秒。0 は不明) */
    long lastLogin;
    int loginDays;
    /** ログイン日数を最後に数えた日(エポック日) */
    long lastLoginDay = NO_DAY;
    int votes;

    PlayerRecord(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    PlayerRecord copy() {
        PlayerRecord c = new PlayerRecord(uuid, name);
        c.firstPlayed = firstPlayed;
        c.total = total;
        c.today = today;
        c.week = week;
        c.month = month;
        c.lastLogin = lastLogin;
        c.loginDays = loginDays;
        c.lastLoginDay = lastLoginDay;
        c.votes = votes;
        return c;
    }
}
