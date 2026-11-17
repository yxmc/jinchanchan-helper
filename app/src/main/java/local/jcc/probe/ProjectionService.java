package local.jcc.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 持有 MediaProjection 会话的前台服务。
 *
 * <p>Android 14+ 要求在取投屏会话之前先有一个 {@code mediaProjection} 类型的前台服务在跑，
 * 且会话只在一次投屏期间有效（系统或用户随时可以终止，终止后必须重新授权）。
 * 因此把会话放在这里，由 {@link ProjectionStream} 负责出帧，{@link CaptureService} 只按需取帧。
 *
 * <p>服务本身不做识别：识别循环仍归无障碍服务，方案切换只决定“从哪拿一帧”。
 */
public class ProjectionService extends Service {
    static final String ACTION_START = "local.jcc.probe.PROJECTION_START";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";
    static final String CHANNEL = "jcc-capture";
    static final int NOTIFICATION_ID = 21;

    /** 最近一次失败的原因，用于界面提示（成功时清空）。 */
    static volatile String lastError = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;

    /**
     * 启动投屏会话：先起前台服务，再在服务内取会话，顺序不能颠倒。
     *
     * @param resultCode {@code onActivityResult} 的 resultCode。它**不是**授权 Intent 里的 extra，
     *                   必须由调用方单独传进来——曾经从 data 里读，导致永远拿到 0，
     *                   日志报“投屏授权结果无效”、投屏永远建立不起来。
     */
    static void start(Context context, int resultCode, Intent permission) {
        Intent intent = new Intent(context, ProjectionService.class)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, permission);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        // 释放由 onDestroy 统一做并只通知一次，避免识别循环收到两条“已停止”而重复刷状态。
        ProjectionStream.stopCurrent("用户停止投屏", false);
        lastError = "";
        context.stopService(new Intent(context, ProjectionService.class));
    }

    /** 会话已被系统/用户终止时调用：把前台服务一起收掉，避免留下一条无用的常驻通知。 */
    static void shutdown(Context context) {
        lastError = "投屏会话已结束，需重新授权";
        context.stopService(new Intent(context, ProjectionService.class));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须先进入前台再取会话，否则 Android 14+ 会直接抛 SecurityException。
        startForegroundCompat();
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);        if (code == 0 || data == null) { fail("投屏授权结果无效"); return START_NOT_STICKY; }
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) { fail("此设备不支持投屏取帧"); return START_NOT_STICKY; }
            // 重新授权时会走到同一个服务实例：先释放旧会话，避免两路投屏同时出帧。
            // 这次替换不通知上层，否则正在跑的识别循环会被误判为“投屏中断”而暂停。
            ProjectionStream.stopCurrent("已由新的授权替换", false);
            if (projection != null) { try { projection.stop(); } catch (RuntimeException ignored) {} projection = null; }
            projection = manager.getMediaProjection(code, data);
            if (projection == null) { fail("获取投屏会话失败"); return START_NOT_STICKY; }
            ProjectionStream.start(this, projection);
            lastError = "";
            CaptureService service = CaptureService.instance;
            if (service != null) service.onProjectionReady();
            else android.util.Log.i("JccAssist", "投屏取帧已就绪，等待无障碍服务连接");
        } catch (RuntimeException e) {
            fail("开启投屏失败：" + e.getMessage());
        }
        return START_NOT_STICKY;
    }

    private void fail(String reason) {
        lastError = reason;
        CaptureService service = CaptureService.instance;
        if (service != null) service.onProjectionFailed(reason);
        main.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    @Override public void onDestroy() {
        boolean wasLive = ProjectionStream.ready();
        // 释放由这里统一做、只通知一次；release 内部会停掉 MediaProjection 会话。
        ProjectionStream.stopCurrent("投屏服务已停止");
        projection = null;
        // 若已经在 fail() 里说明过具体原因，不要用笼统的“已停止”覆盖它。
        if (wasLive || lastError.isEmpty()) {
            lastError = lastError.isEmpty() ? "投屏服务已停止" : lastError;
            CaptureService service = CaptureService.instance;
            if (service != null) service.onProjectionFailed(lastError);
        }
        super.onDestroy();
    }

    private void startForegroundCompat() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "投屏取帧", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("识别期间保持画面取帧通道");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setContentTitle("金铲铲D牌助手 · 投屏取帧")
            .setContentText("正在接收画面用于本地识别，点击可返回设置")
            .setSmallIcon(R.drawable.jcc_notify)
            .setContentIntent(open)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
