package local.jcc.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import java.nio.ByteBuffer;

/**
 * MediaProjection 常驻取帧流：一次授权后持续接收投屏画面，识别循环随时取最新一帧。
 *
 * <p>与无障碍截图的关键差别：系统截图两次之间至少间隔约 334ms，常驻流没有这个门限。
 * 代价是要单独授权（每次会话都要重新确认）、有一个常驻前台通知，部分 ROM 还会显示投屏提示。
 *
 * <p>开销控制：识别循环没在跑时一个像素都不拷贝；在跑时按 {@link #LATCH_MIN_INTERVAL_MS}
 * 节流缓存最近一帧。缓存是为了兜住“画面静止、系统不再合成新帧”的情况——
 * 此时等图会一直等不到，而手里那帧就是当前画面。交付给识别端的始终是副本，因为它会被回收。
 */
final class ProjectionStream {
    /** 取帧回调：成功时交付 ARGB_8888 副本（接收方负责 recycle）；失败时只给原因。 */
    interface Receiver { void onFrame(Bitmap image, long capturedAt, String error); }
    /** 投屏会话被系统终止时的通知，在主线程回调。 */
    interface StopListener { void onProjectionStopped(String reason); }

    /** 等新图的上限；超时说明画面静止没出图，改交付缓存帧。 */
    private static final int NEW_FRAME_WAIT_MS = 120;
    /** 缓存帧的采信上限：超过它说明流真的不出图了（息屏/投屏被拦），不能再拿旧画面去点。 */
    private static final long MAX_LATCH_AGE_MS = 1200;
    /** 识别期间的缓存节流：每 250ms 保留一帧就够识别用，没必要每个合成帧都拷一张全屏图。 */
    private static final long LATCH_ACTIVE_MS = 250;
    /** 暂停识别时的缓存节流：只留一个低频基准帧，恢复识别时立刻有画面可用。 */
    private static final long LATCH_IDLE_MS = 2000;

    private static volatile ProjectionStream current;
    private static volatile StopListener stopListener;

    static ProjectionStream current() { return current; }
    static boolean ready() { ProjectionStream stream = current; return stream != null && stream.isReady(); }
    static void setStopListener(StopListener listener) { stopListener = listener; }
    static void clearStopListener(StopListener listener) { if (stopListener == listener) stopListener = null; }

    private final Context context;
    private final MediaProjection projection;
    private final HandlerThread thread;
    private final Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ImageReader reader;
    private volatile VirtualDisplay display;
    private int width, height, densityDpi;
    /** 主线程会用它判断“是否就绪”，必须可见；因此用 volatile。 */
    private volatile boolean stopped;
    /** 识别循环是否在跑；决定缓存两帧之间的最小间隔。 */
    private volatile boolean active;
    /**
     * 最近一帧画面与它的拍摄时刻。
     *
     * <p>投屏流只在画面变化时出帧，因此“没有更新的帧”就等于“画面没变”，
     * 这帧始终代表当前画面——暂停识别时也不能丢掉它，否则恢复后会白等一轮。
     */
    private Bitmap latest;
    private long latestAt;
    /** 最近一次真正收到**新**图像的时刻（与 latestAt 不同：后者只在拷贝缓存时更新）。 */
    private volatile long freshAt;
    private Receiver pending;
    private String lastError = "";
    private String lastResizeError = "";

    private ProjectionStream(Context context, MediaProjection projection) {
        this.context = context;
        this.projection = projection;
        thread = new HandlerThread("jcc-projection");
        thread.start();
        handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stop("投屏被系统或用户终止"); }
        }, handler);
        handler.post(() -> {
            try {
                createDisplay(screenMetrics(true));
                recordLine("ready " + width + "×" + height);
            } catch (RuntimeException e) {
                lastError = "创建投屏显示失败：" + e.getMessage();
                recordLine("failed " + lastError);
                stop(lastError);
            }
        });
    }

    /** 建立常驻流；同一时间只保留一个，旧会话先释放。 */
    static ProjectionStream start(Context context, MediaProjection projection) {
        ProjectionStream previous = current;
        // 替换旧会话不算“中断”：不通知上层，否则重新授权会把正在跑的识别循环停掉。
        if (previous != null) previous.stop("已被新的投屏会话替换", false);
        ProjectionStream stream = new ProjectionStream(context.getApplicationContext(), projection);
        current = stream;
        return stream;
    }

    static void stopCurrent(String reason) { stopCurrent(reason, true); }

    /** @param notify 是否把这次终止告知上层（识别循环据此暂停并提示重新授权）。 */
    static void stopCurrent(String reason, boolean notify) {
        ProjectionStream stream = current;
        if (stream != null) stream.stop(reason, notify);
    }

    boolean isReady() { return !stopped && reader != null && display != null; }

    void stop(String reason) { stop(reason, true); }

    void stop(String reason, boolean notify) {
        if (stopped) return;
        stopped = true;
        if (current == this) current = null;
        if (handler != null) handler.post(() -> release(reason));
        StopListener listener = stopListener;
        if (notify && listener != null) main.post(() -> listener.onProjectionStopped(reason));
    }

    /**
     * 取一帧当前画面。回调在流的工作线程上执行。
     *
     * @param notBefore 优先接受拍摄时刻不早于它的画面。点击结算后传动作完成时间，
     *                  避免拿到点击前的缓存帧、把已经买到的卡再判一遍。
     *
     * <p>等 {@link #NEW_FRAME_WAIT_MS} 仍没有满足条件的新图时，**仍然交付手里最新的一帧**
     * 并带上它真实的拍摄时刻：到这一步说明画面自上一步以来一个像素都没变，
     * 这帧就是当前画面（典型场景是点击没被游戏接受，画面毫无变化，需要据此重新决策）。
     * 若此时连一帧都没有，才报错让识别循环重试。
     */
    void request(long notBefore, Receiver receiver) {
        if (handler == null) { receiver.onFrame(null, 0, "投屏流未就绪"); return; }
        handler.post(() -> {
            if (!isReady()) {
                receiver.onFrame(null, 0, lastError.isEmpty() ? "投屏流未就绪，请重新授权" : lastError);
                return;
            }
            ensureSize();
            // 有取帧请求就说明识别在跑：缓存按识别节奏走，静止时也能交付手里的那一帧。
            active = true;
            if (latest != null && latestAt >= notBefore) {
                receiver.onFrame(latest.copy(Bitmap.Config.ARGB_8888, false), latestAt, null);
                return;
            }
            if (pending != null) {
                Receiver previous = pending;
                pending = null;
                previous.onFrame(null, 0, "取帧请求被新的请求取代");
            }
            pending = receiver;
            final Receiver expected = receiver;
            handler.postDelayed(() -> {
                if (pending != expected) return;
                pending = null;
                if (latest != null && SystemClock.elapsedRealtime() - latestAt <= MAX_LATCH_AGE_MS) {
                    if (latestAt < notBefore) {
                        // 动作之后画面一个像素都没变（典型是点击被游戏忽略）：这帧就是当前画面，
                        // 交给上层按新一帧重新规划。同时把停滞时长写进日志，便于区分“画面本来静止”
                        // 和“投屏流已经不出图了”。这两种情况从画面本身无法区分。
                        recordLine("stale frame age=" + (SystemClock.elapsedRealtime() - latestAt) +
                            "ms since_action=" + (SystemClock.elapsedRealtime() - notBefore) + "ms");
                    }
                    receiver.onFrame(latest.copy(Bitmap.Config.ARGB_8888, false), latestAt, null);
                    return;
                }
                // 缓存帧太旧：会话可能已经不出图（息屏、投屏被拦）。宁可报错等待，也不按旧画面点击。
                receiver.onFrame(null, 0, "投屏画面长时间无更新");
            }, NEW_FRAME_WAIT_MS);
        });
    }

    private void onImageAvailable(ImageReader source) {
        Image image;
        try {
            image = source.acquireLatestImage();
        } catch (RuntimeException e) {
            // 缓冲被回收得过快时可能抛异常，丢掉这一帧即可。
            return;
        }
        if (image == null) return;
        // 只要系统送来新图就记一次时刻：它证明会话仍在工作，与是否拷贝无关。
        freshAt = SystemClock.elapsedRealtime();
        try {
            Receiver receiver = pending;
            long now = freshAt;
            // 有请求时必拷（请求要的就是这一帧）；没有请求时按方案节流，
            // 暂停识别也要留一个低频基准帧，恢复后立刻有画面。
            if (receiver == null && latest != null && now - latestAt < (active ? LATCH_ACTIVE_MS : LATCH_IDLE_MS)) return;
            Bitmap frame = copy(image);
            if (frame == null) return;
            if (latest != null && !latest.isRecycled()) latest.recycle();
            latest = frame;
            latestAt = now;
            if (receiver != null) {
                pending = null;
                // 交付副本：接收方会回收它，缓存帧要留着自己用。
                receiver.onFrame(frame.copy(Bitmap.Config.ARGB_8888, false), now, null);
            }
        } catch (RuntimeException e) {
            recordLine("frame error " + e.getMessage());
        } finally {
            image.close();
        }
    }

    /** 识别循环进出扫描时切换缓存频率；暂停时保留低频基准帧而不是直接丢掉画面。 */
    void setActive(boolean value) {
        if (handler == null) return;
        handler.post(() -> active = value);
    }

    /** 最近一次收到新图像的时长；上层用它发现“会话还在但没有画面”的异常。仅在识别线程读。 */
    long freshAgeMs() { return freshAt == 0 ? -1 : SystemClock.elapsedRealtime() - freshAt; }

    /** ImageReader 的一帧 → ARGB_8888 位图；行跨距有填充时裁掉右侧多余像素。 */
    private Bitmap copy(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int imageWidth = image.getWidth(), imageHeight = image.getHeight();
        if (pixelStride <= 0 || imageWidth <= 0 || imageHeight <= 0) return null;
        int paddedWidth = imageWidth + Math.max(0, rowStride - pixelStride * imageWidth) / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, imageHeight, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == imageWidth) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, imageWidth, imageHeight);
        padded.recycle();
        return cropped;
    }

    /**
     * 屏幕方向变化后要按新尺寸调整虚拟显示，否则坐标与标定版式对不上。
     *
     * <p>这里只能用 {@link VirtualDisplay#resize} 改尺寸，**不能重建**：
     * Android 14+ 起一个 MediaProjection 会话只允许创建一次 VirtualDisplay，
     * 释放后重新 createVirtualDisplay 会直接抛 SecurityException（表现为切横屏后彻底不出帧）。
     */
    private void ensureSize() {
        DisplayMetrics metrics = screenMetrics(false);
        if (metrics == null) return;
        if (metrics.widthPixels == width && metrics.heightPixels == height) return;
        int w = metrics.widthPixels, h = metrics.heightPixels;
        try {
            display.resize(w, h, metrics.densityDpi);
            // 尺寸变了以后 ImageReader 缓冲也要换成新尺寸，否则出图尺寸仍是旧的。
            ImageReader next = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            next.setOnImageAvailableListener(this::onImageAvailable, handler);
            ImageReader old = reader;
            reader = next;
            display.setSurface(next.getSurface());
            if (old != null) { old.setOnImageAvailableListener(null, null); old.close(); }
            if (latest != null) { latest.recycle(); latest = null; }
            width = w; height = h; densityDpi = metrics.densityDpi;
            lastResizeError = "";
            recordLine("resize " + w + "×" + h);
        } catch (RuntimeException e) {
            // 方向抖动时会反复失败，同一原因只记一次。
            String reason = String.valueOf(e.getMessage());
            if (!reason.equals(lastResizeError)) { lastResizeError = reason; recordLine("resize failed " + reason); }
        }
    }

    private void createDisplay(DisplayMetrics metrics) {
        if (metrics == null) throw new IllegalStateException("读取屏幕尺寸失败");
        int w = metrics.widthPixels, h = metrics.heightPixels, dpi = metrics.densityDpi;
        ImageReader next = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        next.setOnImageAvailableListener(this::onImageAvailable, handler);
        VirtualDisplay created = projection.createVirtualDisplay("jcc-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, next.getSurface(), null, handler);
        if (created == null) { next.close(); throw new IllegalStateException("虚拟显示创建失败"); }
        ImageReader old = reader;
        display = created;
        reader = next;
        width = w; height = h; densityDpi = dpi;
        if (old != null) old.close();
    }

    private DisplayMetrics screenMetrics(boolean required) {
        try {
            DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return null;
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            return metrics.widthPixels > 0 && metrics.heightPixels > 0 ? metrics : null;
        } catch (RuntimeException e) {
            if (required) throw e;
            recordLine("metrics error " + e.getMessage());
            return null;
        }
    }

    private void release(String reason) {
        Receiver receiver = pending;
        pending = null;
        if (receiver != null) receiver.onFrame(null, 0, reason);
        if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; }
        if (display != null) { display.release(); display = null; }
        if (latest != null) { latest.recycle(); latest = null; }
        try { projection.stop(); } catch (RuntimeException ignored) {}
        recordLine("released " + reason);
        if (thread != null) thread.quitSafely();
    }

    /** 日志写到无障碍服务的 trace 里，两种方案的取帧记录集中在一处。 */
    private static void recordLine(String value) {
        CaptureService service = CaptureService.instance;
        if (service != null) service.recordLine("projection " + value);
    }
}
