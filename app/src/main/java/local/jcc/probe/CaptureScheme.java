package local.jcc.probe;

import android.content.SharedPreferences;

/**
 * 取帧方案。两种方案只影响“从哪里拿一帧”，识别、判定与点击流程完全共用。
 *
 * <p>无障碍截图（默认）由系统单次截图，框架限制两次截图至少间隔约 334ms；
 * MediaProjection 常驻流则持续接收投屏画面，识别循环随时取最新一帧，
 * 不受该门限约束（代价是要单独授权、有常驻通知，且部分 ROM 会显示投屏提示）。
 */
final class CaptureScheme {
    static final String PREF = "captureScheme";
    static final String ACCESSIBILITY = "accessibility";
    static final String PROJECTION = "projection";
    static final String[] LABELS = {"无障碍截图", "投屏常驻流"};

    private CaptureScheme() {}

    /** 读取已选方案；未知取值一律按默认的无障碍截图处理。 */
    static String selected(SharedPreferences prefs) {
        return PROJECTION.equals(prefs.getString(PREF, ACCESSIBILITY)) ? PROJECTION : ACCESSIBILITY;
    }

    static boolean isProjection(SharedPreferences prefs) { return PROJECTION.equals(selected(prefs)); }

    static String label(String value) { return PROJECTION.equals(value) ? LABELS[1] : LABELS[0]; }

    /** 下拉框位置 → 方案值。 */
    static String fromIndex(int position) { return position == 1 ? PROJECTION : ACCESSIBILITY; }

    /** 方案值 → 下拉框位置。 */
    static int index(String value) { return PROJECTION.equals(value) ? 1 : 0; }
}
