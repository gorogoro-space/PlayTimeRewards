package space.gorogoro.playtimerewards;

/**
 * ログイン中のプレイヤーの状態。メインスレッドで扱う(lastActivity と afk だけはチャットのスレッドからも読み書きする)。
 */
final class Session {

    /** 最後に操作した時刻 */
    volatile long lastActivity;
    volatile boolean afk;
    /** まだ記録に足していないプレイ時間の始まり。離席中は -1 */
    long segmentStart;
    /** 離席した時刻(最後に操作した時刻) */
    long afkStart;
    /** 今回のログインで記録に足したプレイ時間 */
    long activeMillis;
    /** 今回のログインで離席していた時間(今の離席は含まない) */
    long afkMillis;

    Session(long now) {
        lastActivity = now;
        segmentStart = now;
    }

    /** 今回のログインのプレイ時間(離席を除く) */
    long activeNow(long now) {
        return activeMillis + pending(now);
    }

    /** 今回のログインの離席時間 */
    long afkNow(long now) {
        return afkMillis + (afk ? now - afkStart : 0L);
    }

    /** まだ記録に足していないプレイ時間 */
    long pending(long now) {
        return segmentStart < 0 ? 0L : Math.max(0L, now - segmentStart);
    }
}
