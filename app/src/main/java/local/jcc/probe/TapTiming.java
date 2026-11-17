package local.jcc.probe;

import java.util.Random;

/**
 * 一次手势内连续点按多张卡：每下 20~26ms，卡与卡之间间隔 3~5ms，
 * 五张卡的输入排程控制在 150ms 以内。
 *
 * <p>这是最初版本（v0.11.8）的时序。之所以把间隔压到这么短，是让几张卡的点击
 * 全部落在同一个渲染帧内、由游戏一次性处理完，避开“第一张买成后商店进入结算、
 * 后续点击被丢弃”的问题。间隔拉长到 50~150ms 的版本实测只成第一张。
 */
public final class TapTiming {
    public final int[] starts, durations;
    public final int totalMs;
    public TapTiming(int count, Random random) {
        if (count < 1 || count > 5) throw new IllegalArgumentException();
        starts = new int[count]; durations = new int[count]; int end = 0;
        for (int i = 0; i < count; i++) {
            starts[i] = end; durations[i] = 20 + random.nextInt(7); end += durations[i];
            if (i + 1 < count) end += 3 + random.nextInt(3);
        }
        totalMs = end;
    }
}
