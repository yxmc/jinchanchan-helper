package local.jcc.probe;

import android.content.Context;
import android.graphics.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.concurrent.TimeUnit;

/** Bundled Chinese OCR; crops native phone pixels, without resizing or binarizing. */
final class ShopOcr implements AutoCloseable {
    private final TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    private final Context context;
    /** 卡名字模与对应的卡名，两者一一对应用于快速路径。 */
    private PhoneVision.Template[] templates = new PhoneVision.Template[0];
    private String[] templateNames = new String[0];
    private PhoneVision.Template refreshTemplate;
    private byte[] refreshMask;
    private int offsetX;
    /** 本帧使用的版式，随截图分辨率切换。 */
    private PhoneLayout.Spec spec = PhoneLayout.current();
    private int[] name(int slot) { return PhoneLayout.shift(spec.name(slot), offsetX); }
    private int[] cost(int slot) { return PhoneLayout.shift(spec.cost(slot), offsetX); }
    private int[] gold() { return PhoneLayout.shift(spec.gold, offsetX); }
    private int[] refresh() { return PhoneLayout.shift(spec.refresh, offsetX); }
    /** 费用字模，下标 = 费用 − 1；缺文件的档位为 null。 */
    private PhoneVision.Template[] feeTemplates = new PhoneVision.Template[3];
    /** 已加载的字模目录，按目录名缓存；两机型字号不同，字模不能混用。 */
    private final java.util.Map<String, Object[]> loadedTemplates = new java.util.HashMap<>();
    /** 当前版式没有专用字模，模板快速路径与模板版商店门禁均停用。 */
    private boolean noTemplates;
    private PhoneVision.Template observedRefresh;
    private byte[][] previousSignature;
    private BuyPolicy.Frame previousFrame;
    private boolean cached;
    private volatile boolean recoveryRequested;
    private long previousFrameAt;
    private final ChampionCatalog catalog;
    void requestRecovery() { recoveryRequested = true; }

    boolean cachedLastFrame() { return cached; }

    ShopOcr(Context context, ChampionCatalog catalog) {
        this.catalog = catalog;
        this.context = context;
        loadTemplates(PhoneLayout.current());
    }
    /**
     * 按版式加载字模。机型专用目录缺失时**不回退**到其它机型的字模：
     * 两机字号差 5~7%，套用会掉到 0.5 左右，既过不了门禁也可能在别的卡面上误判。
     * 目录内用 manifest.txt 声明有哪些卡名与费用档，允许只覆盖部分卡池。
     */
    private void loadTemplates(PhoneLayout.Spec target) {
        if (target.templateDir.equals(spec.templateDir) && (templates.length > 0 || noTemplates)) return;
        Object[] cached = loadedTemplates.get(target.templateDir);
        if (cached == null) {
            // manifest.txt：首行是卡名，按 nameN.bin 的顺序；其后是可用费用档（如 "1 2 3"）。
            String[] manifest = readManifest(target.templateDir);
            java.util.List<PhoneVision.Template> names = new java.util.ArrayList<>();
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (int i = 0; i < manifest.length; i++) {
                if (manifest[i].isEmpty()) continue;
                byte[] data = assetOrNull(target.templateDir + "/name" + i + ".bin");
                // 卡名字模必须与其名字同时存在，名字对不上宁可不加载。
                if (data == null) continue;
                names.add(new PhoneVision.Template(data, PhoneLayout.NAME_W, PhoneLayout.NAME_H));
                labels.add(manifest[i]);
            }
            byte[] mask = assetOrNull(target.templateDir + "/refresh.bin");
            PhoneVision.Template[] fees = new PhoneVision.Template[3];
            for (int i = 0; i < fees.length; i++) {
                byte[] data = assetOrNull(target.templateDir + "/fee" + (i + 1) + ".bin");
                if (data != null) fees[i] = new PhoneVision.Template(data, PhoneLayout.COST_W, PhoneLayout.COST_H);
            }
            // 刷新模板缺失会连“商店是否打开”都判不了，按无字模处理。
            cached = mask == null
                ? new Object[]{new PhoneVision.Template[0], new String[0], null, null, fees}
                : new Object[]{names.toArray(new PhoneVision.Template[0]), labels.toArray(new String[0]), mask,
                              new PhoneVision.Template(mask, PhoneLayout.REFRESH_W, PhoneLayout.REFRESH_H), fees};
            loadedTemplates.put(target.templateDir, cached);
        }
        @SuppressWarnings("unchecked") PhoneVision.Template[] names = (PhoneVision.Template[]) cached[0];
        templates = names;
        templateNames = (String[]) cached[1];
        noTemplates = names.length == 0 || cached[2] == null;
        refreshMask = (byte[]) cached[2];
        refreshTemplate = (PhoneVision.Template) cached[3];
        @SuppressWarnings("unchecked") PhoneVision.Template[] fees = (PhoneVision.Template[]) cached[4];
        feeTemplates = fees;
        spec = target;
    }
    /** 读取字模清单；没有清单时返回空数组，等价于“无卡名字模”。 */
    private String[] readManifest(String dir) {
        byte[] data = assetOrNull(dir + "/manifest.txt");
        if (data == null) return new String[0];
        String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        return text.split("\\r?\\n");
    }
    /** 读取可选字模；文件不存在返回 null，其余异常照常抛出。 */
    private byte[] assetOrNull(String name) {
        try (java.io.InputStream in = context.getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] block = new byte[4096]; int n;
            while ((n = in.read(block)) != -1) out.write(block, 0, n);
            return out.toByteArray();
        } catch (java.io.IOException e) { return null; }
    }
    private byte[] asset(String name) {
        try (java.io.InputStream in = context.getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] block = new byte[4096]; int n;
            while ((n = in.read(block)) != -1) out.write(block, 0, n);
            return out.toByteArray();
        } catch (java.io.IOException e) { throw new IllegalStateException("缺少原生模板 " + name, e); }
    }
    static final class Reading {
        final String text;
        final int confidence;
        Reading(String text, int confidence) { this.text = text; this.confidence = confidence; }
    }
    /** 金铲铲单局金币上限；超过即说明金币框切到了其它元素，读数不可信。 */
    private static final int MAX_GOLD = 999;
    /**
     * 整框读数（含左侧金币图标）的采信上限。图标被当成前导数字时读数会跳到 3xx~9xx，
     * 而正常对局金币极少超过 299，因此兜底只在此范围内采信，宁可等下一帧也不按错值决策。
     */
    private static final int MAX_BATCH_GOLD = 299;
    /**
     * 金币位置必须有的白色数字像素下限，用于区分“商店真的开着”和“其它界面的圆形按钮”。
     * 实测 K80：正常商店金币 2（最小一位数）时该区域白像素 696，金币 9 时 732；
     * 选强挑战与装备选择器在同位置显示护符/装备图标，白像素为 0。
     */
    private static final int MIN_GOLD_INK = 200;
    /** 统计裁剪框内的白字像素数，仅用于门禁判定，不做 OCR。 */
    private static int whiteInk(int[] pixels, int width, int[] rect) {
        int count = 0;
        int left = Math.max(0, rect[0]), top = Math.max(0, rect[1]);
        int right = Math.min(width, rect[0] + rect[2]);
        int bottom = Math.min(pixels.length / width, rect[1] + rect[3]);
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            int c = pixels[y * width + x];
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            if (r > 185 && g > 180 && b > 155 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 85) count++;
        }
        return count;
    }
    private Reading read(Bitmap image, int[] rect, boolean digits) {
        Bitmap crop = Bitmap.createBitmap(rect[2] + 16, rect[3] + 16, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(crop);
        canvas.drawColor(Color.rgb(25, 30, 35));
        canvas.drawBitmap(image, new Rect(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]),
            new Rect(8, 8, rect[2] + 8, rect[3] + 8), null);
        try {
            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), 5, TimeUnit.SECONDS);
            int count = 0;
            float confidence = 1;
            for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines())
                for (Text.Element element : line.getElements()) {
                    confidence = Math.min(confidence, element.getConfidence()); count++;
                }
            String text = result.getText().replaceAll(digits ? "[^0-9]" : "[^\\p{IsHan}·]", "");
            return new Reading(text, count == 0 ? 0 : Math.round(confidence * 100));
        } catch (Exception e) { throw new IllegalStateException("OCR 处理失败：" + e.getMessage(), e); }
        finally { crop.recycle(); }
    }
    Reading readCardName(Bitmap image, int slot) { return read(image, name(slot), false); }
    private Reading[] batch(Bitmap image) {
        final int rowHeight = 110;
        Bitmap crop = Bitmap.createBitmap(440, rowHeight * 7, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(crop); canvas.drawColor(Color.rgb(25, 30, 35));
        Paint paint = new Paint();
        for (int i = 0; i < 7; i++) {
            int[] rect = i < 5 ? name(i) : i == 5 ? gold() : refresh();
            canvas.drawBitmap(image, new Rect(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]),
                new Rect(16, i * rowHeight + 16, 16 + rect[2], i * rowHeight + 16 + rect[3]), paint);
            if (i < 5) {
                rect = cost(i);
                canvas.drawBitmap(image, new Rect(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]),
                    new Rect(340, i * rowHeight + 16, 340 + rect[2], i * rowHeight + 16 + rect[3]), paint);
            }
        }
        try {
            Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), 5, TimeUnit.SECONDS);
            @SuppressWarnings("unchecked") java.util.List<Text.Element>[] bins = new java.util.List[12];
            for (int i = 0; i < bins.length; i++) bins[i] = new java.util.ArrayList<>();
            for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) for (Text.Element e : line.getElements()) {
                Rect box = e.getBoundingBox(); if (box == null) continue;
                int row = box.centerY() / rowHeight;
                if (row < 0 || row > 6) continue;
                int bin = row < 5 && box.centerX() >= 320 ? row + 5 : row == 5 ? 10 : row == 6 ? 11 : row;
                bins[bin].add(e);
            }
            Reading[] out = new Reading[12];
            for (int i = 0; i < bins.length; i++) {
                bins[i].sort(java.util.Comparator.comparingInt(e -> e.getBoundingBox().left));
                StringBuilder raw = new StringBuilder(); float confidence = 1;
                for (Text.Element e : bins[i]) { raw.append(e.getText()); confidence = Math.min(confidence, e.getConfidence()); }
                String value = raw.toString().replaceAll(i >= 5 && i <= 10 ? "[^0-9]" : "[^\\p{IsHan}·]", "");
                out[i] = new Reading(value, bins[i].isEmpty() ? 0 : Math.round(confidence * 100));
            }
            return out;
        } catch (Exception e) { throw new IllegalStateException("批量 OCR 失败", e); }
        finally { crop.recycle(); }
    }
    /**
     * 合并两次金币读数。数字区专项读数按白像素列切出数字、结构上就不含左侧金币图标，
     * 因此以它为准。
     *
     * <p>这里不能像早期那样取两者较小值：两条路径读的是同一块数字，
     * 一方因裁切不当少读一位（50 → 0）时，取小值会把错误读数固定下来，
     * 于是金币被误判为 0、看起来“买不起”而卡住不动。
     *
     * <p>只有数字区整块失手（找不到可信数字列）时才退回整框读数，且要求该值在
     * 实际可能持有的金币范围内：整框读数最典型的错误是把左侧金币图标读成前导位
     * （51 → 951），这类值会远超真实金币，宁可判为不可读等下一帧，也不按错值决策。
     */
    private static int pickGold(int batch, int digits) {
        if (digits >= 0 && digits <= MAX_GOLD) return digits;
        return batch >= 0 && batch <= MAX_BATCH_GOLD ? batch : -1;
    }
    /**
     * 只识别金币数字：金币框内左侧是金币图标、右侧是数字。
     * 按列白像素找出数字块的水平范围，裁出该段再 OCR，避免图标花纹被当成数字。
     * 找不到可信数字块时返回 -1。
     */
    private int readGoldDigitsOnly(Bitmap image, int[] pixels) {
        int[] rect = gold();
        int width = image.getWidth();
        int left = Math.max(0, rect[0]), top = Math.max(0, rect[1]);
        int right = Math.min(width, rect[0] + rect[2]), bottom = Math.min(image.getHeight(), rect[1] + rect[3]);
        if (right - left < 8 || bottom - top < 8) return -1;
        // 数字是近白高亮；图标是金色，蓝分量明显更低，用严格白阈值把两者分开。
        int[] colWhite = new int[right - left];
        for (int x = left; x < right; x++) {
            int n = 0;
            for (int y = top; y < bottom; y++) {
                int c = pixels[y * width + x];
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                if (r > 200 && g > 195 && b > 175) n++;
            }
            colWhite[x - left] = n;
        }
        // 找到最右侧那段连续白列，即数字本身（图标在其左侧）。
        // 判空宽度必须与「上一个白列」比较，而不是与 end（最右白列）比较：
        // 单个数字字形就有 30 多列宽，跟 end 比会让整个首位数字落在阈值之外，
        // 只截到末位数字，金币被读成个位数（50 → 0），进而误判买不起而卡住。
        int end = -1, start = -1, lastWhite = -1;
        for (int i = colWhite.length - 1; i >= 0; i--) {
            if (colWhite[i] >= 3) { if (end < 0) end = i; start = i; lastWhite = i; }
            else if (end >= 0 && lastWhite - i > 14) break;
        }
        if (start < 0 || end - start < 4) return -1;
        int padLeft = Math.max(0, start - 3), padRight = Math.min(colWhite.length, end + 4);
        return number(read(image, new int[]{left + padLeft, top, padRight - padLeft, bottom - top}, true));
    }
    private boolean empty(int[] pixels, int width, int[] rect) {        int bright = 0;
        for (int y = rect[1]; y < rect[1] + rect[3]; y++) for (int x = rect[0]; x < rect[0] + rect[2]; x++) {
            int c = pixels[y * width + x];
            if (Color.red(c) > 185 && Color.green(c) > 180 && Color.blue(c) > 155) bright++;
        }
        return bright < 70;
    }
    BuyPolicy.Frame analyze(Bitmap image) {
        cached = false;
        boolean recover = recoveryRequested; recoveryRequested = false;
        BuyPolicy.Frame f = new BuyPolicy.Frame();
        f.width = image.getWidth(); f.height = image.getHeight();
        PhoneLayout.Spec frameSpec = PhoneLayout.find(f.width, f.height);
        f.layoutValid = frameSpec != null;
        if (!f.layoutValid) { f.problem = PhoneLayout.unsupportedMessage(); return f; }
        loadTemplates(frameSpec);
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
        // 没有字模时无从做锚点匹配，只能按标定的零偏移裁剪。
        offsetX = refreshMask == null ? 0 : PhoneLayout.locateOffset(pixels, image.getWidth(), refreshMask, spec);
        f.offsetX = offsetX;
        byte[][] signature = new byte[12][];
        for (int i = 0; i < 5; i++) {
            signature[i] = PhoneVision.mask(pixels, image.getWidth(), name(i));
            signature[i + 5] = PhoneVision.mask(pixels, image.getWidth(), cost(i));
        }
        signature[10] = PhoneVision.mask(pixels, image.getWidth(), gold());
        signature[11] = PhoneVision.mask(pixels, image.getWidth(), refresh());
        if (!recover && previousFrame != null && previousFrame.readable && previousFrame.offsetX == offsetX &&
            previousFrame.width == f.width && previousFrame.height == f.height &&
            android.os.SystemClock.elapsedRealtime() - previousFrameAt < 1200 && java.util.Arrays.deepEquals(signature, previousSignature)) {
            cached = true; return previousFrame;
        }
        PhoneVision.Template refreshSample = new PhoneVision.Template(signature[11], PhoneLayout.REFRESH_W, PhoneLayout.REFRESH_H);
        // 没有本机型专用字模时不能套用别机型的刷新模板，只信 OCR。
        f.shopOpen = (!noTemplates && PhoneVision.score(refreshSample, refreshTemplate) >= .88) ||
            (observedRefresh != null && PhoneVision.score(refreshSample, observedRefresh) >= .90);
        if (!f.shopOpen) {
            f.shopOpen = read(image, refresh(), false).text.contains("刷新");
            if (f.shopOpen) observedRefresh = refreshSample;
        }
        if (!f.shopOpen) return f;
        // 第二道门禁：真正打开商店时，金币数字必然出现在标定位置。
        // 选强挑战、装备选择器等界面右下角是护符/装备图标，那里没有近白色数字（实测白像素为 0），
        // 据此可识别出“刷新按钮误匹配但并非商店”的情况，避免在非商店界面上点击而误消费。
        int goldInk = whiteInk(pixels, image.getWidth(), gold());
        if (goldInk < MIN_GOLD_INK) {
            f.shopOpen = false;
            f.problem = "未在金币位置识别到数字，判定为非商店界面";
            return f;
        }
        Reading[] readings = batch(image);
        int batchGold = number(readings[10]);
        // 金币框只框数字、不含左侧金币图标；再做一次按白像素列精确定位的重识别，
        // 以免批次 OCR 把数字与背景花纹粘在一起。数字区读数为准，详见 pickGold。
        int digitsGold = readGoldDigitsOnly(image, pixels);
        f.gold = pickGold(batchGold, digitsGold);
        // 金铲铲单局金币不会超过上限，超出即判为不可信，宁可等待也不按错值决策。
        if (f.gold > MAX_GOLD) { f.gold = -1; f.problem = "金币读数超出合理范围"; }
        f.readable = f.gold >= 0;
        if (!f.readable && f.problem.isEmpty()) f.problem = "金币区域读取失败";
        for (int i = 0; i < 5; i++) {
            if (empty(pixels, image.getWidth(), name(i))) { f.names[i] = ""; continue; }
            String known = null;
            byte[] mask = signature[i];
            PhoneVision.Template sample = new PhoneVision.Template(mask, PhoneLayout.NAME_W, PhoneLayout.NAME_H);
            double best = .90;
            if (!noTemplates) for (int j = 0; j < templates.length; j++) {
                double score = PhoneVision.score(sample, templates[j]);
                // 模板来自参考截图；只有当前赛季目录里同名棋子存在时才采信。
                if (score >= best && catalog.has(templateNames[j])) { best = score; known = templateNames[j]; }
            }
            Reading name = readings[i];
            if (recover && known == null && name.text.isEmpty()) name = read(image, name(i), false);
            f.names[i] = known != null ? known : name.text.isEmpty() ? null : name.text;
            if (known == null && name.text.isEmpty()) {
                f.readable = false;
                f.problem += (f.problem.isEmpty() ? "" : "；") + "第 " + (i + 1) + " 格暂未读到卡名";
            }
            f.costs[i] = number(readings[i + 5]);
            byte[] feeMask = signature[i + 5];
            PhoneVision.Template feeSample = new PhoneVision.Template(feeMask, PhoneLayout.COST_W, PhoneLayout.COST_H);
            double feeBest = .88;
            for (int n = 0; n < feeTemplates.length; n++) {
                // 该档费用字模可能缺失（只标定了部分费用），跳过而不是当成不匹配。
                if (feeTemplates[n] == null) continue;
                double score = PhoneVision.score(feeSample, feeTemplates[n]);
                if (score >= feeBest) { feeBest = score; f.costs[i] = n + 1; }
            }
            if (f.costs[i] < 1 || f.costs[i] > 5) {
                Integer expected = catalog.cost(f.names[i]);
                if (expected != null) f.costs[i] = expected;
                else if (recover) f.costs[i] = number(read(image, cost(i), true));
            }
        }
        if (f.readable) { previousFrame = f; previousSignature = signature; previousFrameAt = android.os.SystemClock.elapsedRealtime(); }
        return f;
    }
    private int number(Reading reading) {
        if (reading.text.isEmpty() || reading.text.length() > 3) return -1;
        try { return Integer.parseInt(reading.text); } catch (NumberFormatException e) { return -1; }
    }
    @Override public void close() { recognizer.close(); }
}
