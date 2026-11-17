package local.jcc.probe;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.Display;
import android.os.*;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.SystemClock;

public class CaptureService extends AccessibilityService {
    static volatile CaptureService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean busy;
    private final ShopActionGate actionGate = new ShopActionGate();
    private AssistTrace trace;
    private long captureRequest, lastFrameTrace, waitSince, lastRecovery;
    private String waitMessage = "";
    private volatile String phase = "idle";
    private volatile long phaseSince;
    private final Runnable health = new Runnable() {
        @Override public void run() {
            if (scanning) record("health phase=" + phase + " phase_ms=" + (SystemClock.elapsedRealtime() - phaseSince) +
                " busy=" + busy + " panel=" + choosingDeck + " status=" + lastStatus +
                " capture=" + captureScheme() + " projection_fresh_ms=" + projectionFreshAge());
            handler.postDelayed(this, 1000);
        }
    };
    /** 投屏流最近一次出图距今的时长；非投屏方案或流未就绪时返回 -1。 */
    private long projectionFreshAge() {
        ProjectionStream stream = ProjectionStream.current();
        return stream == null ? -1 : stream.freshAgeMs();
    }
    private void record(String value) { if (trace != null) trace.record(value); }
    /** 供其它类（投屏取帧流）写入同一份诊断日志。 */
    void recordLine(String value) { record(value); }
    private void phase(String value) { phase = value; phaseSince = SystemClock.elapsedRealtime(); }

    private boolean scanning;
    private boolean choosingDeck;
    private String deckName = "默认卡组";
    private long generation;
    private String foreground = "";
    private ScanOverlay overlay;
    private ShopOcr recognizer;
    private LineupImporter lineupImporter;
    private BuyPolicy policy;
    private boolean previewOnly;
    private long lastScreenshot;
    private int screenshotIntervalMs = 334;
    private static final int MAX_ACTION_FRAME_AGE_MS = 250;
    /** 投屏常驻流的最小取帧间隔。常驻流没有系统门限，但识别一轮本身就要 100ms+，
     *  完全不限速只会白拷全屏位图；150ms 已明显快于截图方案的 334ms。 */
    private static final int PROJECTION_MIN_INTERVAL_MS = 150;
    /** 投屏取帧的整体超时；超过说明会话没在出帧，回到等待而不是卡住识别循环。 */
    private static final int PROJECTION_TIMEOUT_MS = 1200;
    /** 投屏未授权时识别循环的重试间隔。这是稳定状态，按取帧节奏空转只会白刷日志。 */
    private static final int PROJECTION_RETRY_MS = 2000;
    /** 动作闸门被占用时的轮询间隔：保证占用方异常掉线后识别循环仍能被唤醒。 */
    private static final int ACTION_GATE_POLL_MS = 120;
    /**
     * 投屏取帧只接受不早于此时刻的画面。点击/刷新之后必须往后推，
     * 否则从常驻流拿到点击前的缓存帧，会把已经买到的卡再判一遍、甚至重复点击。
     */
    private long frameNotBefore;
    /** 最近一帧距拍摄时刻的延迟（仅投屏方案有值），用于日志区分“画面旧”和“识别慢”。 */
    private long lastFrameLagMs;
    /** 投屏会话被终止时由 {@link ProjectionStream} 回调，用于把原因写进日志并提示重新授权。 */
    private final ProjectionStream.StopListener projectionStopped = this::onProjectionStopped;
    /** 买卡手势完成后的渲染等待；v0.11.8 原始值为 100ms（刷新为 350ms）。 */
    private static final int BUY_SETTLE_MS = 100;
    /**
     * 手势被系统取消后的恢复等待。取消通常意味着输入事务没送达/系统正忙，
     * 立刻重试会继续占用输入通道，让“点不动”持续更久。
     */
    private static final int CANCEL_BACKOFF_MS = 600;
    private long lastRefreshCompleted;
    private String lastStatus = "";
    private java.util.List<String> targets;
    private final Runnable scanNext = this::scan;
    private final Runnable capture = this::captureNow;
    @Override protected void onServiceConnected() {
        instance = this;
        if (trace == null) trace = new AssistTrace(this);
        record("service connected version=" + appVersion());
        ProjectionStream.setStopListener(projectionStopped);
        handler.removeCallbacks(health); handler.postDelayed(health, 1000);
        status("服务已连接。可显示悬浮窗进行商店识别。");
    }
    /** 取帧方案。两种方案共用识别、判定与点击流程，只切换“从哪拿一帧”。 */
    String captureScheme() { return CaptureScheme.selected(getSharedPreferences("probe", MODE_PRIVATE)); }
    /**
     * 切换取帧方案。切换后新的取帧从这里开始；
     * 切回无障碍截图会释放投屏会话（连同常驻通知），不在后台白占显示资源。
     */
    void setCaptureScheme(String scheme) {
        String value = CaptureScheme.PROJECTION.equals(scheme) ? CaptureScheme.PROJECTION : CaptureScheme.ACCESSIBILITY;
        if (value.equals(captureScheme())) return;
        getSharedPreferences("probe", MODE_PRIVATE).edit().putString(CaptureScheme.PREF, value).apply();
        generation++; handler.removeCallbacks(scanNext);
        if (value.equals(CaptureScheme.ACCESSIBILITY)) ProjectionService.stop(this);
        attachProjection();
        if (value.equals(CaptureScheme.PROJECTION) && !ProjectionStream.ready())
            status("已切换到投屏常驻流，请在首页点“授权投屏取帧”");
        else status("取帧方案：已切换到" + CaptureScheme.label(value));
        if (scanning && !choosingDeck) handler.post(scanNext);
    }
    /** 投屏会话就绪；扫描中会立刻恢复识别循环。 */
    void onProjectionReady() {
        record("projection session ready scheme=" + captureScheme());
        if (scanning) attachProjection();
        if (scanning && CaptureScheme.isProjection(getSharedPreferences("probe", MODE_PRIVATE))) {
            status("投屏取帧已就绪，继续识别");
            handler.removeCallbacks(scanNext); handler.post(scanNext);
        }
    }
    /**
     * 让投屏流进入“正在识别”状态（缓存频率提高）。
     * 投屏流只在画面变化时出帧，静止画面必须靠缓存帧反复交付，因此扫描期间要开它。
     */
    private void attachProjection() {
        ProjectionStream stream = ProjectionStream.current();
        if (stream == null) return;
        // 用截图方案时不要打开投屏缓存：那会每 250ms 拷一张全屏图，纯属白烧内存带宽。
        boolean wanted = scanning && CaptureScheme.isProjection(getSharedPreferences("probe", MODE_PRIVATE));
        stream.setActive(wanted);
    }
    /** 投屏会话不可用（授权失败/服务停止）；扫描中先暂停并提示，避免空转。 */
    void onProjectionFailed(String reason) {
        record("projection unavailable " + reason);
        // 会话已结束：把前台服务一并收掉，否则会留一条无用的常驻通知。
        if (!ProjectionStream.ready()) ProjectionService.shutdown(this);
        if (scanning && CaptureScheme.isProjection(getSharedPreferences("probe", MODE_PRIVATE)))
            stopScanning("投屏取帧中断：" + reason + "，请重新授权后再开启");
    }
    private void onProjectionStopped(String reason) { onProjectionFailed(reason); }
    /** 从包信息读版本名，避免日志里的版本号随发版写死在源码中。 */
    private String appVersion() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Exception e) { return "?"; }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        // Ignore our overlay; the current application window is rechecked before each gesture.
        if (pkg.equals(getPackageName())) {
            if (event.getClassName() != null && event.getClassName().toString().equals(MainActivity.class.getName())) {
                foreground = pkg;
                if (scanning) waitForGame("已开启 · 等待进入游戏");
            }
            return;
        }
        foreground = pkg;
        if (scanning && !isGamePackage(pkg) && !pkg.equals("com.android.systemui") && !isGameForeground())
            waitForGame("已开启 · 等待返回游戏");
    }
    @Override public void onInterrupt() { cancelLineup();stopScanning("无障碍服务被中断"); }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (scanning) waitForGame("已开启 · 等待游戏横屏");
        if (overlay != null) overlay.onRotation();
    }
    void showOverlay() {
        try {
            if (overlay == null) overlay = new ScanOverlay(this);
            overlay.show();
            status("点击开启后，商店打开就自动识别购买");
        } catch (RuntimeException e) { status("显示悬浮窗失败：" + e.getMessage()); }
    }
    void hideOverlay() { cancelLineup(); stopScanning("悬浮窗已关闭"); if (overlay != null) { overlay.remove(); overlay = null; } }
    void setAutoRefresh(boolean enabled) {
        getSharedPreferences("probe", MODE_PRIVATE).edit().putBoolean("autoRefresh", enabled).apply();
        if (policy != null) policy.setAutoRefresh(enabled);
        status(enabled ? "自动刷新已开启" : "自动刷新已关闭，等待手动刷新");
    }
    int goldReserve() { return getSharedPreferences("probe", MODE_PRIVATE).getInt("reserve", GoldReserve.DEFAULT); }
    /** 当前识别使用的赛季目录。 */
    int championSet() { return ChampionCatalog.selectedSet(getSharedPreferences("probe", MODE_PRIVATE)); }
    /**
     * 切换赛季目录。目录影响搜索选卡、费用兜底和阵容头像识别；
     * 已在扫描时重建识别器，并取消进行中的阵容导入，避免沿用上赛季数据。
     */
    void setChampionSet(int set) {
        boolean supported = false;
        for (int value : ChampionCatalog.SETS) if (value == set) supported = true;
        if (!supported) throw new IllegalArgumentException("不支持的赛季");
        getSharedPreferences("probe", MODE_PRIVATE).edit().putInt("championSet", set).apply();
        cancelLineup();
        generation++; handler.removeCallbacks(scanNext);
        // 识别器只在工作线程内创建和关闭，重建也排到同一线程，避免与进行中的识别交错。
        worker.execute(() -> { if (recognizer != null) { recognizer.close(); recognizer = null; } });
        status("棋子目录已切换到 " + ChampionCatalog.label(set));
        if (scanning && !choosingDeck) handler.postDelayed(scanNext, 120);
    }
    void setGoldReserve(int value) {
        if (value < 0 || value > 999) throw new IllegalArgumentException("保留金币应为 0～999");
        generation++; handler.removeCallbacks(scanNext);
        getSharedPreferences("probe", MODE_PRIVATE).edit().putInt("reserve", value).apply();
        if (policy != null) policy.setReserve(value);
        status("自动刷新保留金币：" + value + "，不限制目标卡购买");
        if (scanning && !choosingDeck) handler.post(scanNext);
    }
    void startScanning() {
        if (scanning) return;
        if (isImporting()) { scanning=true;previewOnly=getSharedPreferences("probe",MODE_PRIVATE).getBoolean("previewOnly",false);status("阵容导入完成后自动拿卡");return; }
        android.content.SharedPreferences prefs = getSharedPreferences("probe", MODE_PRIVATE);
        DeckStore.Deck deck;
        try {
            deck = new DeckStore(this).current();
            deckName = deck.name;
            targets = BuyPolicy.parseTargets(deck.targets);
            // 阵容码卡组首次使用要导入一次以抓取英雄名单；但阵容导入只在已标定的机型可用，
            // 未标定的机型若名单已经填好，就直接按名单拿卡，否则会卡在永远不能完成的导入流程里。
            if (deck.isCode() && !deck.verified) {
                if (lineupSupported()) {
                    if (targets.isEmpty() || deck.preview.isEmpty()) {
                        scanning = true; previewOnly = prefs.getBoolean("previewOnly", false);
                        requestLineupImport(deck.id); return;
                    }
                    status("请先核对并确认此阵容的英雄名单"); return;
                }
                if (targets.isEmpty()) {
                    status("此设备暂不支持阵容导入，请在“购买目标”里填写英雄名后保存");
                    return;
                }
                record("lineup skipped: unsupported device, using manual targets=" + targets.size());
            }
            if (targets.isEmpty()) throw new IllegalArgumentException("请先填写目标名字");
            policy = new BuyPolicy(targets, prefs.getInt("reserve", GoldReserve.DEFAULT), prefs.getBoolean("autoRefresh", false));
        } catch (IllegalArgumentException e) { status(e.getMessage()); return; }
        previewOnly = prefs.getBoolean("previewOnly", false);
        scanning = true;
        lastRefreshCompleted = 0;
        generation++;
        record("scan start deck=" + deckName + " verified=" + deck.verified + " code=" + deck.isCode() +
            " lineup_supported=" + lineupSupported() + " targets=" + targets + " previewOnly=" + previewOnly +
            " autoRefresh=" + policy.isAutoRefresh() + " reserve=" + goldReserve() + " set=" + championSet() +
            " capture=" + captureScheme() + " projection_ready=" + ProjectionStream.ready());
        if (CaptureScheme.isProjection(prefs) && !ProjectionStream.ready())
            status("取帧方案为投屏常驻流，但尚未授权：请先回首页点“授权投屏取帧”");
        else status("正在加载中文 OCR · 目标：" + targets);
        attachProjection();
        if (overlay != null) overlay.dockBelowShop();
        handler.post(scanNext);
    }
    /** 阵容导入是否已在当前机型标定；未标定机型不允许进入导入流程。 */
    private boolean lineupSupported() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        return PhoneLayout.supportsLineup(longSide, shortSide) || PhoneLayout.supportsLineup(shortSide, longSide);
    }
    String currentDeckName() { return new DeckStore(this).current().name; }
    void selectDeck(String id) {
        cancelLineup();
        DeckStore.Deck deck = new DeckStore(this).select(id);
        deckName = deck.name;
        targets = BuyPolicy.parseTargets(deck.targets);
        android.content.SharedPreferences prefs = getSharedPreferences("probe", MODE_PRIVATE);
        generation++;
        handler.removeCallbacks(scanNext);
        policy = buyPolicyFor(deck, prefs);
        status("当前阵容：" + deckName);
        if (scanning && !choosingDeck) handler.post(scanNext);
    }
    /**
     * 卡组能否直接拿卡。阵容码卡组在已标定机型上需先导入确认名单；
     * 未标定机型无法导入，只要名单非空就按名单拿卡。
     */
    private BuyPolicy buyPolicyFor(DeckStore.Deck deck, android.content.SharedPreferences prefs) {
        if (targets.isEmpty()) return null;
        if (deck.isCode() && !deck.verified && lineupSupported()) return null;
        return new BuyPolicy(targets, prefs.getInt("reserve", GoldReserve.DEFAULT), prefs.getBoolean("autoRefresh", false));
    }
    boolean isImporting() { return lineupImporter != null && lineupImporter.active(); }
    void selectDeckFromOverlay(String id) {
        cancelLineup(); selectDeck(id);
        DeckStore.Deck deck = new DeckStore(this).current();
        if (!deck.isCode()) return;
        if (lineupSupported()) requestLineupImport(id);
        else {
            // 未标定机型不能导入：保持已填名单直接拿卡，并说明原因。
            if (policy == null) status("此设备不支持阵容导入，请先在“购买目标”填写英雄名");
            else status("此设备不支持阵容导入，已按现有名单拿卡");
        }
    }
    void requestLineupImport(String id) {
        cancelLineup(); selectDeck(id);generation++;handler.removeCallbacks(scanNext);
        DeckStore.Deck deck = new DeckStore(this).current();
        if (!deck.isCode()) { status("此阵容没有阵容码"); return; }
        // 双保险：即使调用方漏判，也不允许在未标定机型上启动导入。
        if (!lineupSupported()) { status("此设备暂不支持阵容导入，请手动填写英雄名"); return; }
        lineupImporter = new LineupImporter(this,deck);lineupImporter.begin();
    }
    private void cancelLineup() { if (lineupImporter != null) { lineupImporter.cancel();lineupImporter=null; } }
    boolean importAllowed() { return !choosingDeck && actionGate.delay(SystemClock.elapsedRealtime())==0 && isGameForeground(); }
    void importStatus(String text) { if(!text.equals(lastStatus))record("lineup "+text);status(text); }
    void scheduleLineup(Runnable task,long delay) { handler.postDelayed(task,delay); }
    void removeLineup(Runnable task) { handler.removeCallbacks(task); }
    void runLineupWork(Runnable task) { if (!worker.isShutdown()) worker.execute(task); }
    BuyPolicy.Frame readShopForLineup(Bitmap image) {
        if (recognizer==null) recognizer=new ShopOcr(this,ChampionCatalog.of(this,championSet()));
        return recognizer.analyze(image);
    }
    void lineupFinished(LineupImporter owner,boolean success,String message) {
        if (lineupImporter!=owner) return;lineupImporter=null;
        DeckStore.Deck deck=new DeckStore(this).current();targets=BuyPolicy.parseTargets(deck.targets);
        android.content.SharedPreferences prefs=getSharedPreferences("probe",MODE_PRIVATE);
        policy=buyPolicyFor(deck,prefs);
        if (!success || policy==null) scanning=false;
        status(message);
        // Toast 必须在有 Looper 的线程上弹：`finish()` 是在 worker 线程里回调进来的，
        // 直接 show() 会抛 "Animators may only be run on Looper threads"，把整条导入流程
        // 连同「已应用」的结果一起吞掉——设备日志里表现成「导入失败：阵容画面暂未读清」。
        final String toast=message;
        handler.post(()->{try{android.widget.Toast.makeText(this,toast,android.widget.Toast.LENGTH_LONG).show();}
            catch(RuntimeException e){android.util.Log.w("JccAssist","提示无法显示",e);}});
        if(scanning&&!choosingDeck)handler.post(scanNext);
    }
    boolean captureForLineup(java.util.function.Function<Bitmap,LineupVision.Screen> reader,
        java.util.function.BiConsumer<LineupVision.Screen,Throwable> done) {
        if (busy || !importAllowed() || SystemClock.elapsedRealtime()-lastScreenshot<screenshotIntervalMs) return false;
        busy=true;lastScreenshot=SystemClock.elapsedRealtime();final long request=++captureRequest;final long started=lastScreenshot;phase("lineupScreenshot");
        Runnable timeout=()->{if(request!=captureRequest||!busy)return;captureRequest++;busy=false;done.accept(null,new IOException("阵容截图回调超时"));};
        handler.postDelayed(timeout,1600);
        try { takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult result){
                handler.removeCallbacks(timeout);if(request!=captureRequest){result.getHardwareBuffer().close();return;}
                Bitmap hardware=null,image=null;
                try(HardwareBuffer buffer=result.getHardwareBuffer()) {
                    hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hardware==null)throw new IOException("截图为空");
                    image=hardware.copy(Bitmap.Config.ARGB_8888,false);if(image==null)throw new IOException("截图复制失败");
                    final Bitmap captured=image;image=null;
                    worker.execute(()->{LineupVision.Screen screen=null;Throwable failure=null;
                        try {phase("lineupOCR");screen=reader.apply(captured);}catch(Throwable e){failure=e;}finally{captured.recycle();}
                        final LineupVision.Screen answer=screen;final Throwable error=failure;
                        handler.post(()->{busy=false;phase("idle");
                            if(error==null && SystemClock.elapsedRealtime()-started>1600){if(answer!=null&&answer.preview!=null)answer.preview.recycle();done.accept(null,new IOException("阵容识别结果过旧，等待新画面"));}
                            else { if(answer!=null)record("lineup_screen ms="+(SystemClock.elapsedRealtime()-started)+" menu="+answer.menu+" replacement="+answer.replacement+" shop="+answer.shopOpen+" board="+answer.board+" title="+answer.targetMatches+" applied="+answer.targetApplied);done.accept(answer,error); }
                        });
                    });
                }catch(Exception e){if(image!=null)image.recycle();busy=false;done.accept(null,e);}finally{if(hardware!=null)hardware.recycle();}
            }
            @Override public void onFailure(int code){handler.removeCallbacks(timeout);if(request!=captureRequest)return;busy=false;done.accept(null,new IOException("截图错误 "+code));}
        }); }catch(Exception e){handler.removeCallbacks(timeout);busy=false;done.accept(null,e);}
        return true;
    }
    boolean tapForLineup(android.graphics.Point point,LineupImporter owner,Runnable completed) {
        if (lineupImporter!=owner||!owner.active()||!importAllowed())return false;
        if (overlay!=null&&overlay.blocksPoint(point.x,point.y)) { overlay.dockBelowShop();return false; }
        final long gestureId=actionGate.begin(BuyPolicy.Kind.BUY,SystemClock.elapsedRealtime());generation++;handler.removeCallbacks(scanNext);
        record("lineup_tap id="+gestureId+" point="+point.x+","+point.y);phase("lineupGesture");
        android.graphics.Path path=new android.graphics.Path();path.moveTo(point.x,point.y);
        android.accessibilityservice.GestureDescription gesture=new android.accessibilityservice.GestureDescription.Builder()
            .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path,0,40)).build();
        final boolean[] finished={false};Runnable timeout=()->{if(finished[0])return;finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());owner.fail("阵容按钮点击回调超时，已停止导入");};
        handler.postDelayed(timeout,1800);
        try {boolean accepted=dispatchGesture(gesture,new GestureResultCallback(){
            @Override public void onCompleted(android.accessibilityservice.GestureDescription g){handler.removeCallbacks(timeout);if(finished[0])return;finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());if(owner.active())completed.run();}
            @Override public void onCancelled(android.accessibilityservice.GestureDescription g){handler.removeCallbacks(timeout);if(finished[0])return;finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());owner.fail("阵容按钮点击被取消，已停止导入");}
        },handler);if(!accepted){handler.removeCallbacks(timeout);finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());return false;}return true;
        }catch(RuntimeException e){handler.removeCallbacks(timeout);finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());return false;}
    }
    /**
     * 在阵容卡片行上横向拖动一段距离，让右侧被裁掉的卡片滚进可视区。
     *
     * <p>**只做水平拖动、纵向坐标不变**：阵容行的卡片是可以左右滑动的横向列表，
     * 10 个英雄的阵容里第 10 张会停在面板右边界外，只露出一条边框（实测只露出约 10px），
     * 头像比对取不到有效描迹，那一张就永远读不到。滑动只改变横向偏移，不改变行号。
     *
     * <p>起点取卡片行中部偏左（避开最右侧的滚动条与「应用/取消」按钮），
     * 终点按调用方给的位移量算，并夹在面板可视区内。
     */
    boolean swipeForLineup(android.graphics.Point from,int dx,LineupImporter owner,Runnable completed) {
        if (lineupImporter!=owner||!owner.active()||!importAllowed())return false;
        if (overlay!=null&&overlay.blocksPoint(from.x,from.y)) { overlay.dockBelowShop();return false; }
        final long gestureId=actionGate.begin(BuyPolicy.Kind.BUY,SystemClock.elapsedRealtime());generation++;handler.removeCallbacks(scanNext);
        record("lineup_swipe id="+gestureId+" from="+from.x+","+from.y+" dx="+dx);phase("lineupGesture");
        android.graphics.Path path=new android.graphics.Path();path.moveTo(from.x,from.y);
        path.lineTo(from.x+dx,from.y);
        // 300ms 比点击长得多：太快的拖动会被游戏当成擦碰而不触发滚动。
        android.accessibilityservice.GestureDescription gesture=new android.accessibilityservice.GestureDescription.Builder()
            .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path,0,300)).build();
        final boolean[] finished={false};Runnable timeout=()->{if(finished[0])return;finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());owner.fail("阵容滑动回调超时，已停止导入");};
        handler.postDelayed(timeout,2000);
        try {boolean accepted=dispatchGesture(gesture,new GestureResultCallback(){
            @Override public void onCompleted(android.accessibilityservice.GestureDescription g){handler.removeCallbacks(timeout);if(finished[0])return;finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());if(owner.active())completed.run();}
            @Override public void onCancelled(android.accessibilityservice.GestureDescription g){handler.removeCallbacks(timeout);if(finished[0])return;finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());owner.fail("阵容滑动被取消，已停止导入");}
        },handler);if(!accepted){handler.removeCallbacks(timeout);finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());return false;}return true;
        }catch(RuntimeException e){handler.removeCallbacks(timeout);finished[0]=true;actionGate.finish(gestureId,SystemClock.elapsedRealtime());return false;}
    }
    void beginDeckSelection() {        choosingDeck = true; generation++;
        lastRefreshCompleted = 0;
        handler.removeCallbacks(scanNext);
        if (policy != null) policy.shopClosed();
    }
    void endDeckSelection() {
        choosingDeck = false;
        if (scanning) { handler.removeCallbacks(scanNext); handler.post(scanNext); }
    }
    void toggleScanning() {
        if (scanning) stopScanning("已手动暂停"); else startScanning();
    }
    private void retryFrame(String message) {
        if (!scanning) return;
        status(message);
        handler.removeCallbacks(scanNext);
        if (!choosingDeck) handler.postDelayed(scanNext, frameIntervalMs());
    }
    /** 当前方案的取帧间隔：投屏流不必等系统的 334ms 截图门限。 */
    private int frameIntervalMs() {
        return CaptureScheme.isProjection(getSharedPreferences("probe", MODE_PRIVATE))
            ? PROJECTION_MIN_INTERVAL_MS : screenshotIntervalMs;
    }
    private void waitForGame(String message) {
        if (!scanning) return;
        generation++;
        if (policy != null) policy.shopClosed();
        retryFrame(message);
    }
    /** 上次记录的门禁原因与时间，用于只记状态变化、避免每秒刷屏。 */
    private String lastGateReason = "";
    private long lastGateAt;
    /**
     * 记录“为什么这一轮没有识别”。同一原因持续时每分钟只记一条，原因变化时立即记录，
     * 这样日志里能直接看出卡在哪一道门禁，而不会被重复行淹没。
     */
    private void diagnoseGate(String reason, String detail) {
        long now = SystemClock.elapsedRealtime();
        if (reason.equals(lastGateReason) && now - lastGateAt < 60000) return;
        lastGateReason = reason; lastGateAt = now;
        record("gate " + reason + " (" + detail + ") foreground=" + foreground + " policy=" + (policy != null) +
            " scanning=" + scanning + " panel=" + choosingDeck + " importing=" + isImporting());
    }
    private static boolean isGamePackage(String pkg) { return pkg.equals("com.tencent.jkchess") || pkg.equals("com.tencent.jk"); }
    private boolean isGameForeground() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                    !(window.isActive() || window.isFocused())) continue;
                android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (root.getPackageName() != null) {
                        foreground = root.getPackageName().toString();
                        return isGamePackage(foreground);
                    }
                } finally { root.recycle(); }
            }
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) try {
                if (root.getPackageName() != null) { foreground = root.getPackageName().toString(); return isGamePackage(foreground); }
            } finally { root.recycle(); }
        } catch (RuntimeException e) { android.util.Log.w("JccAssist", "读取前台窗口失败", e); }
        // Never authorize a gesture solely from an old event's package name.
        return false;
    }
    void stopScanning(String reason) {
        if (reason.contains("手动暂停")) cancelLineup();
        scanning = false; generation++;
        handler.removeCallbacks(scanNext);
        // 停止识别后投屏流降到低频基准帧，不再持续拷贝画面。
        attachProjection();
        status(reason);
    }
    private void scan() {
        if (!scanning || choosingDeck || isImporting()) return;
        if (policy == null) { retryFrame("请先导入阵容码并核对英雄名单"); return; }
        long actionDelay = actionGate.delay(SystemClock.elapsedRealtime());
        // 闸门被占用时不能只是 return：正常情况由 endGesture 续跑，但一旦占用方漏了释放，
        // 就再没有任何东西会调 scan()，识别循环会静默停摆（表现为“突然不买卡了”）。
        // 这里仍然排一次重试，闸门自身也带超时自愈，两条路都不依赖对方。
        if (actionDelay < 0) { handler.postDelayed(scanNext, ACTION_GATE_POLL_MS); return; }
        if (actionDelay > 0) { handler.removeCallbacks(scanNext); handler.postDelayed(scanNext, actionDelay); return; }
        if (!isGameForeground()) { diagnoseGate("not_foreground", "前台不是游戏"); waitForGame("已开启 · 等待游戏和商店"); return; }
        if (overlay != null && overlay.overlapsShop()) {
            overlay.dockBelowShop();
            status("正在把悬浮窗移到商店下方……");
            handler.postDelayed(scanNext, 16); return;
        }
        if (busy) { handler.postDelayed(scanNext, 8); return; }
        boolean projection = CaptureScheme.isProjection(getSharedPreferences("probe", MODE_PRIVATE));
        if (projection) {
            // 常驻流没有系统的截图间隔门限，只保留一个远小于它的自节流。
            if (!ProjectionStream.ready()) {
                diagnoseGate("projection_not_ready", "投屏流未就绪");
                // 未授权是稳定状态，不能按 150ms 空转重试；等用户去授权或换方案。
                status("投屏取帧未授权或已中断，请在首页重新授权");
                handler.removeCallbacks(scanNext);
                handler.postDelayed(scanNext, PROJECTION_RETRY_MS);
                return;
            }
            long remaining = PROJECTION_MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastScreenshot);
            if (remaining > 0) { handler.postDelayed(scanNext, remaining); return; }
        } else {
            long remaining = screenshotIntervalMs - (SystemClock.elapsedRealtime() - lastScreenshot);
            if (remaining > 0) { handler.postDelayed(scanNext, remaining); return; }
        }
        busy = true;
        lastScreenshot = SystemClock.elapsedRealtime();
        final long token = generation, started = SystemClock.elapsedRealtime();
        final long request = ++captureRequest;
        phase("screenshot");
        if (projection) {
            captureProjection((image, capturedAt, error) -> {
                if (request != captureRequest) { if (image != null) image.recycle(); return; }
                phase("copy");
                if (image == null) {
                    phase("idle"); busy = false;
                    if (token == generation) retryFrame("投屏暂未出帧：" + error);
                    return;
                }
                if (!scanning || token != generation) { image.recycle(); busy = false; return; }
                // 帧龄统一从“拿到这一帧”算起，和截图方案一致：它衡量的是识别耗时，
                // 而不是这帧在流里缓存了多久（缓存多久由 frameNotBefore 把关）。
                long acquired = SystemClock.elapsedRealtime();
                lastFrameLagMs = capturedAt <= 0 ? 0 : acquired - capturedAt;
                worker.execute(() -> analyze(image, token, acquired));
            });
            return;
        }
        final Runnable screenshotTimeout = () -> {
            if (request != captureRequest || !busy || !"screenshot".equals(phase)) return;
            captureRequest++; busy = false;
            record("screenshot callback timeout");
            if (scanning) { generation++; retryFrame("截图回调超时，重新取帧"); }
        };
        handler.postDelayed(screenshotTimeout, 1500);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    handler.removeCallbacks(screenshotTimeout);
                    if (request != captureRequest) { result.getHardwareBuffer().close(); return; }
                    phase("copy");
                    Bitmap image = null, hardware = null;
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware == null) throw new IOException("截图缓冲区为空");
                        image = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        if (image == null) throw new IOException("截图复制失败");
                        if (!scanning || token != generation) { image.recycle(); busy = false; return; }
                        final Bitmap captured = image;
                        worker.execute(() -> analyze(captured, token, started));
                        image = null;
                    } catch (Exception e) {
                        if (image != null && !image.isRecycled()) image.recycle();
                        busy = false; if (token == generation) retryFrame("截图暂未成功，继续等待");
                    } finally { if (hardware != null) hardware.recycle(); }
                }
                @Override public void onFailure(int code) {
                    handler.removeCallbacks(screenshotTimeout);
                    if (request != captureRequest) return;
                    phase("idle"); busy = false;
                    if (token == generation) {
                        if (code == 3) screenshotIntervalMs = Math.min(360, screenshotIntervalMs + 4);
                        if (code == 2) stopScanning("无障碍截图权限失效，请重新开启服务");
                        else retryFrame("等待下一帧，截图错误码 " + code);
                    }
                }
            });
        } catch (Exception e) { handler.removeCallbacks(screenshotTimeout); phase("idle"); busy = false; retryFrame("等待截图恢复：" + e.getMessage()); }
    }
    /** 投屏取帧回调：成功给画面与拍摄时刻，失败只给原因。 */
    private interface ProjectionFrame { void onFrame(Bitmap image, long capturedAt, String error); }
    /**
     * 从投屏常驻流取一帧。画面静止时系统不再合成新帧，{@link ProjectionStream} 会在短暂等待后
     * 交付缓存帧（画面没变，就是当前画面）；只在流确实不出图时才报错，让识别循环继续重试。
     */
    private void captureProjection(ProjectionFrame done) {
        final boolean[] finished = {false};
        Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            record("projection frame timeout");
            done.onFrame(null, 0, "投屏暂未出帧");
        };
        handler.postDelayed(timeout, PROJECTION_TIMEOUT_MS);
        ProjectionStream stream = ProjectionStream.current();
        if (stream == null) {
            handler.removeCallbacks(timeout); finished[0] = true;
            done.onFrame(null, 0, "投屏取帧未授权或已中断，请在首页重新授权");
            return;
        }
        stream.request(frameNotBefore, (image, capturedAt, error) -> handler.post(() -> {
            if (finished[0]) { if (image != null) image.recycle(); return; }
            finished[0] = true;
            handler.removeCallbacks(timeout);
            if (image == null) done.onFrame(null, 0, error == null ? "投屏暂未出帧" : error);
            else done.onFrame(image, capturedAt, null);
        }));
    }
    private void analyze(Bitmap image, long token, long started) {
        BuyPolicy.Frame result = null;
        phase("OCR");
        long ocrStarted = SystemClock.elapsedRealtime();
        boolean reused = false;
        String error = null;
        try {
            if (recognizer == null) recognizer = new ShopOcr(this, ChampionCatalog.of(this, championSet()));
            // 每次识别都同步目标名单：卡名 OCR 的一位错别字只向名单内收敛，不会认成别的卡。
            recognizer.setTargets(targets);
            result = recognizer.analyze(image);
            reused = recognizer.cachedLastFrame();
        } catch (Throwable e) { error = e.toString(); }
        finally { image.recycle(); }
        final BuyPolicy.Frame answer = result;
        final long ocrMs = SystemClock.elapsedRealtime() - ocrStarted;
        final boolean reusedFrame = reused;
        final String failure = error;
        handler.post(() -> {
            phase("idle"); busy = false;
            if (!scanning || token != generation) return;
            if (answer == null) { record("OCR failure ms=" + ocrMs + " error=" + failure); retryFrame("本帧识别暂未成功，继续等待：" + failure); return; }
            if (!answer.layoutValid) { waitForGame("已开启 · " + answer.width + "×" + answer.height + " 不在支持范围，等待横屏"); return; }
            if (previewOnly) {
                status("仅识别 · " + answer.summary() + "\n商店=" + answer.shopOpen + " 可读=" + answer.readable +
                    " · " + (SystemClock.elapsedRealtime() - started) + "ms\n" + answer.problem);
                handler.post(scanNext); return;
            }
            BuyPolicy.Decision action = policy.decide(answer);
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameTrace >= 1000 || ocrMs >= 600) {
                record("frame_ms=" + (now-started) + " ocr_ms=" + ocrMs + " cached=" + reusedFrame +
                    " frame_lag_ms=" + lastFrameLagMs +
                    " action=" + action.kind + " reason=" + action.message + " readable=" + answer.readable +
                    " shop=" + answer.shopOpen + " offset_x=" + answer.offsetX + " " + answer.summary() + " costs=" + java.util.Arrays.toString(answer.costs) + " problem=" + answer.problem);
                lastFrameTrace = now;
            }
            // 商店未打开是最常见的“看起来没反应”，单独记一条，便于区分是没开商店还是识别失败。
            if (!answer.shopOpen) diagnoseGate("shop_closed", "截图可读但未识别到刷新按钮");
            if (action.kind == BuyPolicy.Kind.WAIT && answer.shopOpen) {
                if (!waitMessage.equals(action.message)) { waitMessage = action.message; waitSince = now; }
                if (now - waitSince >= 1200 && now - lastRecovery >= 1200 && recognizer != null) {
                    recognizer.requestRecovery(); lastRecovery = now;
                    record("request OCR recovery reason=" + action.message);
                }
            } else { waitMessage = ""; waitSince = now; }
            String stats = "\n已买 " + policy.bought + " · 已刷新 " + policy.refreshed;
            if (action.kind == BuyPolicy.Kind.STOP) { stopScanning(action.message + stats); return; }
            status(action.message + "\n" + answer.summary() + stats);
            if (action.kind == BuyPolicy.Kind.WAIT) { handler.post(scanNext); return; }
            long age = SystemClock.elapsedRealtime() - started;
            android.util.Log.i("JccPerf", "frame_ms=" + age + " ocr_ms=" + ocrMs + " cached=" + reusedFrame + " interval_ms=" + frameIntervalMs());
            if (age > MAX_ACTION_FRAME_AGE_MS) {
                status("识别预热或耗时较长，等待新帧"); handler.post(scanNext); return;
            }
            tap(token, action, answer, started);
        });
    }
    private void tap(long token, BuyPolicy.Decision action, BuyPolicy.Frame frame, long frameStarted) {
        if (!scanning || token != generation) return;
        PhoneLayout.Spec spec = PhoneLayout.find(frame.width, frame.height);
        if (spec == null) { waitForGame("已开启 · 等待游戏横屏"); return; }
        if (action.kind == BuyPolicy.Kind.REFRESH && !policy.isAutoRefresh()) {
            status("自动刷新已关闭，取消待执行刷新"); handler.post(scanNext); return;
        }
        if (!isGameForeground()) { waitForGame("已开启 · 等待返回游戏"); return; }
        if (SystemClock.elapsedRealtime() - frameStarted > MAX_ACTION_FRAME_AGE_MS) {
            status("等待更新的游戏画面"); handler.post(scanNext); return;
        }
        if (overlay != null && overlay.overlapsShop()) {
            overlay.dockBelowShop(); handler.post(scanNext); return;
        }
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        // v0.11.8 原始方案：一次截图识别完成后，把本帧所有可买的目标卡塞进同一个手势点完，
        // 卡间仅 3~5ms（五张合计 150ms 以内），让所有点击落在同一渲染帧里由游戏一次处理。
        int count = action.kind == BuyPolicy.Kind.BUY ? action.slots.length : 1;
        TapTiming timing = new TapTiming(count, random);
        android.accessibilityservice.GestureDescription.Builder builder = new android.accessibilityservice.GestureDescription.Builder();
        for (int i = 0; i < count; i++) {
            // 点击点严格取自本帧所用版式，避免与识别时的裁剪坐标系不一致。
            float x = (action.kind == BuyPolicy.Kind.BUY
                ? spec.nameX[action.slots[i]] + spec.tapCardDx
                : spec.tapRefreshX) + frame.offsetX + random.nextInt(-9, 10);
            float y = (action.kind == BuyPolicy.Kind.BUY ? spec.tapCardY : spec.tapRefreshY) + random.nextInt(-12, 13);
            if (overlay != null && overlay.blocksPoint(x, y)) {
                overlay.dockBelowShop(); retryFrame("正在移开悬浮窗"); return;
            }
            record("tap index=" + i + "/" + count + " slot=" + (action.kind == BuyPolicy.Kind.BUY ? action.slots[i] : -1) +
                " x=" + Math.round(x) + " y=" + Math.round(y) + " at=" + timing.starts[i] + " hold=" + timing.durations[i]);
            android.graphics.Path path = new android.graphics.Path(); path.moveTo(x, y);
            builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, timing.starts[i], timing.durations[i]));
        }
        final android.accessibilityservice.GestureDescription gesture = builder.build();
        final long dispatchStarted = SystemClock.elapsedRealtime();
        if (actionGate.delay(dispatchStarted) != 0) { handler.post(scanNext); return; }
        final long gateId = actionGate.beginSequence(action.kind, dispatchStarted);
        final long actionToken = ++generation;
        handler.removeCallbacks(scanNext);
        policy.sent(action, frame);
        phase("gesture");
        record("dispatch gate=" + gateId + " generation=" + actionToken + " action=" + action.kind +
            " slots=" + java.util.Arrays.toString(action.slots) + " total_ms=" + timing.totalMs +
            " offset=" + frame.offsetX + " " + frame.summary());
        final boolean[] finished = {false};
        final Runnable timeout = () -> {
            finished[0] = true;
            record("gesture callback timeout action=" + action.kind);
            if (scanning) stopScanning("点击回调超时，已暂停，请重新开启自动拿卡");
        };
        handler.postDelayed(timeout, 1200);
        try {
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(android.accessibilityservice.GestureDescription g) {
                    handler.removeCallbacks(timeout);
                    if (finished[0]) return;
                    finished[0] = true;
                    endGesture(action, actionToken, gateId, timing.totalMs);
                }
                @Override public void onCancelled(android.accessibilityservice.GestureDescription g) {
                    handler.removeCallbacks(timeout);
                    if (finished[0]) return;
                    finished[0] = true;
                    record("cancelled action=" + action.kind);
                    // 手势被系统取消：这一下没送达游戏，必须无条件释放闸门。
                    // 漏放会让下一次 scan() 在 delay()<0 处直接 return，识别循环彻底停摆，
                    // 且重新点「开启」也救不回来（只能重启服务）。
                    actionGate.abort(SystemClock.elapsedRealtime());
                    phase("idle");
                    // 停一拍再恢复，避免继续占用紧张的输入通道。
                    if (scanning) { handler.removeCallbacks(scanNext); handler.postDelayed(() -> retryFrame("点击被取消，等待当前商店确认"), CANCEL_BACKOFF_MS); }
                }
            }, handler);
            if (!accepted) {
                handler.removeCallbacks(timeout); finished[0] = true;
                actionGate.abort(SystemClock.elapsedRealtime()); phase("idle");
                stopScanning("系统拒绝手势，请确认无障碍服务已重新启用");
            }
        } catch (RuntimeException e) {
            handler.removeCallbacks(timeout); finished[0] = true;
            actionGate.abort(SystemClock.elapsedRealtime()); phase("idle");
            stopScanning("点击失败：" + e.getMessage());
        }
    }
    /** 本次决策的所有点击都发完，等游戏结算后再取下一帧。 */
    private void endGesture(BuyPolicy.Decision action, long actionToken, long gateId, int totalMs) {
        actionGate.finishSequence(gateId, SystemClock.elapsedRealtime());
        record("completed action=" + action.kind + " total_ms=" + totalMs);
        phase("idle");
        if (!scanning || actionToken != generation) return;
        // 只接受动作完成之后的画面：常驻流里还可能是点击前的缓存帧，
        // 拿它决策会把已经买到的卡再判一次、甚至重复点击。
        frameNotBefore = SystemClock.elapsedRealtime();
        if (action.kind == BuyPolicy.Kind.REFRESH) lastRefreshCompleted = SystemClock.elapsedRealtime();
        handler.removeCallbacks(scanNext);
        handler.postDelayed(scanNext, action.kind == BuyPolicy.Kind.BUY ? BUY_SETTLE_MS : 120);
    }

    void scheduleCapture() {
        stopScanning("连续识别已暂停");
        if (busy) return;
        busy = true;
        status("已预约：8 秒后截图，请切换到游戏。");
        handler.postDelayed(capture, 8000);
    }
    private void status(String value) {
        if (value.equals(lastStatus)) return;
        lastStatus = value;
        getSharedPreferences("probe", MODE_PRIVATE).edit().putString("status", value).apply();
        // 悬浮窗更新必须在主线程：阵营导入的收尾工作跑在 worker 线程上，直接调 overlay.update()
        // 会抛 "Animators may only be run on Looper threads"，把整个导入结果吞掉。
        final String text=value;
        Runnable apply=()->{ try { if (overlay != null) overlay.update(text, scanning); }
            catch (RuntimeException e) { android.util.Log.w("JccAssist", "更新悬浮窗失败", e); } };
        if (android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) apply.run();
        else handler.post(apply);
        android.util.Log.i("JccAssist", value);
    }
    private void captureNow() {
        status("正在截取并保存……");
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap hardware = null;
                    Bitmap software = null;
                    try {
                        hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware == null) throw new IOException("无法读取截图缓冲区");
                        software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        if (software == null) throw new IOException("无法复制截图");
                        final Bitmap image = software;
                        worker.execute(() -> save(image));
                        software = null;
                    } catch (Exception e) { status("截图处理失败：" + e); busy = false; }
                    finally {
                        if (software != null) software.recycle();
                        if (hardware != null) hardware.recycle();
                        buffer.close();
                    }
                }
                @Override public void onFailure(int code) {
                    String reason;
                    switch (code) {
                        case 2: reason = "无障碍权限不可用"; break;
                        case 3: reason = "截图间隔过短"; break;
                        case 4: reason = "显示屏无效"; break;
                        case 5: reason = "窗口无效"; break;
                        case 6: reason = "窗口含受保护内容，系统拒绝截图"; break;
                        default: reason = "系统内部错误";
                    }
                    status("截图失败，错误码 " + code + "：" + reason);
                    busy = false;
                }
            });
        } catch (Exception e) { status("调用截图失败：" + e); busy = false; }
    }
    private void save(Bitmap image) {
        Uri uri = null;
        try {
            File temp = new File(getFilesDir(), "pending.png");
            try (OutputStream stream = new FileOutputStream(temp)) {
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("PNG 编码失败");
            }
            File latest = new File(getFilesDir(), "latest.png");
            if (!temp.renameTo(latest)) throw new IOException("预览文件保存失败");
            String name = "JCC_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JccProbe");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法创建相册图片");
            try (InputStream in = new FileInputStream(latest); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("无法写入相册");
                byte[] bytes = new byte[65536];
                int count;
                while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
            }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            final String message = "截图成功：" + image.getWidth() + " × " + image.getHeight() + "\nPictures/JccProbe/" + name + "\n请检查原图中游戏内容是否正常；成功返回不代表画面非黑屏。";
            handler.post(() -> status(message));
        } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            final String message = "保存失败：" + e + "\n可尝试查看本地预览。";
            handler.post(() -> status(message));
        } finally { image.recycle(); handler.post(() -> busy = false); }
    }
    @Override public void onDestroy() {
        scanning = false; generation++;cancelLineup();
        ProjectionStream.clearStopListener(projectionStopped);
        handler.removeCallbacks(scanNext);
        if (overlay != null) { overlay.remove(); overlay = null; }
        handler.removeCallbacks(capture); handler.removeCallbacks(health);
        if (trace != null) trace.close();
        if (instance == this) instance = null;
        worker.execute(() -> { if (recognizer != null) { recognizer.close(); recognizer = null; } });
        worker.shutdown();
        super.onDestroy();
    }
}
