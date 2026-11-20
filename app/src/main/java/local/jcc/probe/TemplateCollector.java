package local.jcc.probe;

import android.content.Context;
import java.io.*;
import java.util.*;

/**
 * 卡名字模自举采集：用 OCR 的成功识别结果反向生成字模，逐步补齐卡池覆盖。
 *
 * <p>**为什么需要它**：字模匹配比 OCR 稳得多（自匹配 1.00，跨卡交叉最高 0.45），
 * 但随包字模只覆盖极少数棋子——平板上原来是 5 / 119 = 4%，绝大多数卡只能靠 ML Kit 硬读，
 * 实测整帧读不到卡名占 40%、部分缺格占 39%，能正常决策的帧只有 21%。
 * 手动为 119 个棋子截 24~36 张图并逐个标定不现实，所以改成「边玩边攒」。
 *
 * <p>**三重校验**（缺一不可，否则会把误读固化成错字模，比没有更糟）：
 * <ol>
 *   <li>名字必须在当前赛季棋池里——实测 OCR 常把「璐璐」读成「璐路」「瑞璐」，
 *       这类错字不在棋池中，第一道就过滤掉；</li>
 *   <li>同一格必须**连续 MIN_CONFIRM 次读到同一个名字**，且掩码两两自匹配
 *       ≥ {@link #SELF_SIMILAR}——偶发误读过不了这道；</li>
 *   <li>与已有字模的交叉匹配必须 &lt; {@link #CROSS_SIMILAR}——防止同一张卡被重复入库，
 *       也防止两个不同卡名对应同一形状（那说明至少有一个是错的）。</li>
 * </ol>
 *
 * <p>采集结果落在 app 私有目录，由人工导出后随包发布；运行时**不会**自动写 assets
 * （assets 是只读的，且让运行时改字模会带来不可控的漂移）。
 */
final class TemplateCollector {
    /** 同一格连续多少次读到同名字才确认。3 次能滤掉绝大多数抖动，又不会太慢。 */
    private static final int MIN_CONFIRM = 3;
    /** 同一名字相邻两次采样的掩码自匹配下限。低于它说明字形还没稳定（动画/闪烁）。 */
    private static final double SELF_SIMILAR = 0.95;
    /** 与已有字模的交叉相似度上限。超过它就不入库，避免重复或形状冲突。 */
    private static final double CROSS_SIMILAR = 0.80;
    /**
     * 「读不出的格子」按形状稳定入库所需的连续帧数。比 {@link #MIN_CONFIRM} 放宽，
     * 因为这里没有任何名字做交叉验证，唯一的依据就是「同一格连续多次字形完全一致」。
     */
    private static final int UNKNOWN_CONFIRM = 5;
    /**
     * 单字卡名的墨迹上限。实测双字卡名墨迹 1100~1500、单字约 580~680，
     * 用 900 能把两者干净分开。**必须区分**：只有一个字时不知道是「慎」还是「烬」，
     * 而 S16 里「慎」「烬」「蔚」「彗」都是单字，字形各不相同但都占一个字宽。
     */
    private static final int SINGLE_NAME_INK_MAX = 900;
    /** 落盘「未知」掩码用的前缀，避免与真卡名文件混淆。 */
    private static final String UNKNOWN_PREFIX = "unknown-";

    /** 每格最近一次「候选」：名字 + 掩码 + 连续命中次数。 */
    private static final class Candidate {
        String name;
        byte[] mask;
        int streak;
        /** 该候选是否为「名字读不出、只靠形状稳定」的未知卡（落盘时用形状哈希命名）。 */
        boolean unknown;
    }

    private final Context context;
    /** 已存在字模的卡名 → 掩码，用于交叉查重（含随包字模与本次已采集的）。 */
    private final Map<String, byte[]> accepted = new LinkedHashMap<>();
    private final Candidate[] candidates = new Candidate[5];
    private ChampionCatalog catalog;
    private boolean enabled;

    TemplateCollector(Context context) {
        this.context = context;
        for (int i = 0; i < candidates.length; i++) candidates[i] = new Candidate();
        // 主动建一次目录：既让「采集已开启」这件事在文件系统里可见（便于用 adb 查进度），
        // 也让后面写入不必每次都判目录是否存在。
        dir();
        loadExisting();
    }

    /** 采集开关。默认开启；只在已标定机型且字模目录存在时工作。 */
    void setEnabled(boolean value) { enabled = value; }
    void setCatalog(ChampionCatalog value) { catalog = value; }

    /** 采集目录：app 私有文件区，避免污染 assets。 */
    private File dir() {
        File d = new File(context.getFilesDir(), "templates-learned");
        if (!d.exists() && !d.mkdirs()) return null;
        return d;
    }

    /**
     * 载入「已经有字模」的卡名与形状，用于查重。
     *
     * <p>包括随包字模（assets 里的 manifest）和之前采集到的（files 目录）。
     * 读不到就当作空集合，采集照样进行——只是查重能力弱一些。
     */
    private void loadExisting() {
        // 随包字模：manifest.txt 按 nameN.bin 顺序逐行列卡名。
        try (InputStream manifest = context.getAssets().open(PhoneLayout.current().templateDir + "/manifest.txt")) {
            byte[] bytes = manifest.readAllBytes();
            String[] lines = new String(bytes, "UTF-8").split("\n");
            int index = 0;
            for (String raw : lines) {
                String name = raw.trim();
                if (name.isEmpty()) continue;
                try (InputStream in = context.getAssets().open(PhoneLayout.current().templateDir + "/name" + index + ".bin")) {
                    accepted.put(name, in.readAllBytes());
                } catch (IOException ignored) { /* 清单与文件不匹配时跳过该条 */ }
                index++;
            }
        } catch (IOException ignored) { /* 没有随包字模 */ }
        // 已采集字模：文件名就是 <卡名>.bin，不需要清单。
        File[] files = dir() == null ? null : dir().listFiles((d, n) -> n.endsWith(".bin"));
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4);
            if (accepted.containsKey(name)) continue;
            try (InputStream in = new FileInputStream(file)) { accepted.put(name, in.readAllBytes()); }
            catch (IOException ignored) { /* 单个文件坏了不影响其它 */ }
        }
    }

    /**
     * 每帧调用一次，喂进本帧识别出的卡名与卡名掩码。
     *
     * <p>只喂「名字与掩码都拿到」的格子；调用方负责过滤空位与读不出的格子。
     * 内部做三重校验，通过后落盘。
     */
    void observe(byte[][] nameMasks, String[] names) {
        if (!enabled || catalog == null || nameMasks == null || names == null) return;
        for (int slot = 0; slot < candidates.length && slot < names.length; slot++) {
            String name = names[slot];
            byte[] mask = nameMasks[slot];
            // 名字必须是棋池内的真名，否则就是 OCR 误读（「璐路」「瑞璐」都不在池里）。
            if (name == null || name.isEmpty() || mask == null || !catalog.has(name)) {
                candidates[slot] = new Candidate();
                continue;
            }
            if (accepted.containsKey(name)) { candidates[slot] = new Candidate(); continue; }
            Candidate c = candidates[slot];
            boolean sameName = !c.unknown && name.equals(c.name);
            boolean sameShape = sameName && c.mask != null && selfSimilar(c.mask, mask) >= SELF_SIMILAR;
            if (sameName && sameShape) c.streak++;
            else { c = new Candidate(); c.name = name; c.mask = mask; c.streak = 1; candidates[slot] = c; }
            if (c.streak < MIN_CONFIRM) continue;
            // 三重校验全部通过才入库。
            if (conflicts(name, mask)) { candidates[slot] = new Candidate(); continue; }
            if (save(name, mask)) { accepted.put(name, mask); candidates[slot] = new Candidate(); }
        }
    }

    /**
     * 收集「名字读不出、但有稳定字形」的格子，落盘成 `unknown-<形状哈希>.bin` 供人工认领。
     *
     * <p>**为什么必须有这条路径**：字模缺失与 OCR 失败会互相锁死——某张卡没有字模时，
     * 只能靠 ML Kit 硬读；而单字卡名（如 S16 的「烬」）在商店里最容易读不出，
     * 于是 {@link #observe} 那条「必须有名字」的路径**永远采不到它**，
     * 六份真机日志里「烬」出现 0 次就是这条死循环的直接后果。
     *
     * <p>这里放弃「名字必须已知」的前提，改用**形状稳定性**代替：同一位连续
     * {@link #UNKNOWN_CONFIRM} 帧掩码两两自匹配 ≥{@link #SELF_SIMILAR}、且与已有字模
     * 交叉相似度 <{@link #CROSS_SIMILAR}，就落盘一份。名字留空，等人工把字形渲染出来
     * 认领后再用 `tools/import_learned_templates.py` 按真名合并——**绝不自动猜名字**，
     * 猜错等于把错字模打进包，比没有更糟。
     */
    void observeUnread(byte[][] nameMasks, boolean[] unread) {
        if (!enabled || nameMasks == null || unread == null) return;
        for (int slot = 0; slot < candidates.length && slot < nameMasks.length; slot++) {
            byte[] mask = nameMasks[slot];
            if (!unread[slot] || mask == null) { if (candidates[slot].unknown) candidates[slot] = new Candidate(); continue; }
            int ink = 0; for (byte b : mask) if (b != 0) ink++;
            // 墨迹太少必然是空位/边框；太多说明框里混进了相邻元素。两者都不值得采集。
            if (ink < 200) { if (candidates[slot].unknown) candidates[slot] = new Candidate(); continue; }
            if (ink > SINGLE_NAME_INK_MAX + 300) { if (candidates[slot].unknown) candidates[slot] = new Candidate(); continue; }
            Candidate c = candidates[slot];
            boolean sameShape = c.unknown && c.mask != null && selfSimilar(c.mask, mask) >= SELF_SIMILAR;
            if (sameShape) c.streak++;
            else { c = new Candidate(); c.unknown = true; c.mask = mask; c.streak = 1; candidates[slot] = c; }
            if (c.streak < UNKNOWN_CONFIRM) continue;
            // 与已有字模过于相似说明这张卡本来就有字模（只是这帧没命中），不必重复采。
            if (conflicts(UNKNOWN_PREFIX, mask)) { candidates[slot] = new Candidate(); continue; }
            String key = UNKNOWN_PREFIX + shapeKey(mask);
            if (accepted.containsKey(key) || save(key, mask)) {
                accepted.put(key, mask); candidates[slot] = new Candidate();
            }
        }
    }

    /** 掩码的形状指纹：按列/行墨迹投影量化，稳定且与像素错位无关。 */
    private static String shapeKey(byte[] mask) {
        long h = 1125899906842597L;
        int w = PhoneLayout.NAME_W, hh = PhoneLayout.NAME_H;
        for (int x = 0; x < w; x++) {
            int n = 0;
            for (int y = 0; y < hh; y++) if (mask[y * w + x] != 0) n++;
            h = h * 31 + n;
        }
        return Long.toHexString(h & 0x7fffffff);
    }

    /** 与已有字模交叉查重：同名字已存在、或与别的卡名过于相似，都不入库。 */
    private boolean conflicts(String name, byte[] mask) {
        if (accepted.containsKey(name)) return true;
        PhoneVision.Template sample = null;
        for (Map.Entry<String, byte[]> entry : accepted.entrySet()) {
            if (sample == null) sample = new PhoneVision.Template(mask, PhoneLayout.NAME_W, PhoneLayout.NAME_H);
            byte[] other = entry.getValue();
            if (other == null || other.length != mask.length) continue;
            double score = PhoneVision.score(sample, new PhoneVision.Template(other, PhoneLayout.NAME_W, PhoneLayout.NAME_H));
            if (score >= CROSS_SIMILAR) return true;
        }
        return false;
    }

    private static double selfSimilar(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        return PhoneVision.score(a, b, PhoneLayout.NAME_W, PhoneLayout.NAME_H);
    }

    private boolean save(String name, byte[] mask) {
        File d = dir();
        if (d == null) return false;
        File target = new File(d, name + ".bin");
        try (FileOutputStream out = new FileOutputStream(target)) { out.write(mask); return true; }
        catch (IOException e) { return false; }
    }

    /** 已采集数量，供界面显示进度。 */
    /** 已采集数量，供界面显示进度。**不计 unknown-\* 待认领文件**，避免进度虚高。 */
    int learnedCount() {
        File d = dir();
        if (d == null) return 0;
        String[] files = d.list((dir, n) -> n.endsWith(".bin") && !n.startsWith(UNKNOWN_PREFIX));
        return files == null ? 0 : files.length;
    }
    /** 待人工认领的「读不出」字模数量，供排查时用 adb 看进度。 */
    int pendingUnknownCount() {
        File d = dir();
        if (d == null) return 0;
        String[] files = d.list((dir, n) -> n.endsWith(".bin") && n.startsWith(UNKNOWN_PREFIX));
        return files == null ? 0 : files.length;
    }
}
