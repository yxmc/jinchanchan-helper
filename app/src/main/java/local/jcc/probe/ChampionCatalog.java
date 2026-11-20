package local.jcc.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.*;
import java.io.*;
import java.util.*;

/** 随包棋子目录：按赛季加载目录 JSON 与头像子目录，默认第 18 赛季。 */
final class ChampionCatalog {
    static final int DEFAULT_SET = 18;
    /** 支持的赛季；顺序即界面下拉顺序。 */
    static final int[] SETS = {18, 16};
    private static final Map<Integer, ChampionCatalog> cache = new HashMap<>();
    static final class Champion {
        final String id, name; final int cost; final boolean costAmbiguous;
        Champion(JSONObject o) throws JSONException {
            id = o.getString("id"); name = o.getString("name"); cost = o.getInt("price");
            // 上游偶有同名不同费的条目（如 S16 伊泽瑞尔 4/5 费），此时不用目录猜费用。
            costAmbiguous = o.has("priceVariants");
        }
    }
    final int setId;
    private final AssetManager assets;
    private final String portraitDir;
    private final List<Champion> champions = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private final Map<String, Integer> costs = new HashMap<>();
    private final Map<String, Bitmap> portraits = new HashMap<>();

    private ChampionCatalog(Context context, int set) {
        setId = set; assets = context.getAssets();
        String file = "champions_s" + set + ".json";
        try (InputStream in = assets.open(file)) {
            JSONObject root = new JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            portraitDir = "portraits/" + root.optString("portraitDir", "s" + set);
            JSONArray rows = root.getJSONArray("champions");
            for (int i = 0; i < rows.length(); i++) {
                Champion champion = new Champion(rows.getJSONObject(i));
                champions.add(champion);
                names.add(champion.name);
                if (!champion.costAmbiguous) costs.putIfAbsent(champion.name, champion.cost);
            }
        } catch (IOException | JSONException e) { throw new IllegalStateException("棋子目录读取失败：" + file, e); }
    }
    /** 同一赛季的目录与头像只保留一份；读取失败时抛出可展示的异常。 */
    static ChampionCatalog of(Context context, int set) {
        synchronized (cache) {
            ChampionCatalog catalog = cache.get(set);
            if (catalog == null) { catalog = new ChampionCatalog(context, set); cache.put(set, catalog); }
            return catalog;
        }
    }
    /** 读取已保存的赛季；不认识的值回退到默认赛季，避免旧版本设置导致启动失败。 */
    static int selectedSet(SharedPreferences prefs) {
        int set = prefs.getInt("championSet", DEFAULT_SET);
        for (int supported : SETS) if (supported == set) return set;
        return DEFAULT_SET;
    }
    static String label(int set) { return "S" + set; }
    List<Champion> champions() { return champions; }
    /** 目录中的费用，用于 OCR 费用读取失败时兜底；名字不存在或费用有多个版本时返回 null。 */
    Integer cost(String name) { return costs.get(name); }
    /** 该名字是否属于当前赛季目录。 */
    boolean has(String name) { return names.contains(name); }
    /**
     * 把 OCR 读到的卡名校正为**目标名单**里的卡名；校正不出来时返回原样。
     *
     * <p>OCR 对中文卡名有稳定的写错倾向（实测「科加斯」常被读成「料加斯」、
     * 「波比」读成「破比」、「德莱文」读成「德菜文」），而目标匹配是精确字符串比对，
     * 错一个字就会漏买，表现为「明明在商店里却只买了其中几张」。
     *
     * <p>只向 {@code targets}（用户设定的几人名单）收敛，而不是向整个棋池收敛：
     * 棋池里存在大量互为一次错字的卡名（双字如 赛恩/薇恩/永恩/奥恩、艾克/艾希、
     * 凯南/凯隐；三字如 约里克/沃里克/塔里克、雷克塞/雷克顿、金克丝/拉克丝），
     * 向全池归一化会把一张卡认成另一张，那就买错卡了。目标通常只有十来个名字，
     * 组内几乎不会互为错字，向它收敛既安全又能补回漏买。
     *
     * <p>**但「组内不互为错字」这条假设不成立**：目标里有「金克丝」时，OCR 正确读出的
     * 「拉克丝」只差一个字，会被改写成「金克丝」并当成目标买掉；目标里有「沃里克」时
     * 「约里克」「塔里克」同理；目标里有单字的「蔚」时，任何单字卡名（洛/霞/慎/易/烬/彗）
     * 都会被改写成「蔚」。真正的 OCR 错字有个干净的共性——**错字本身不在棋池里**
     * （「料加斯」「破比」「德菜文」都不是合法卡名），而「拉克丝」「塔里克」都是。
     * 所以这里先判「原名是否已是棋池真名」：是就不收敛，直接原样返回。
     */
    String normalize(String raw, java.util.Collection<String> targets) {
        return normalize(raw, targets, names);
    }
    /**
     * {@link #normalize(String, java.util.Collection)} 的实现，棋池单独传入以便离线回归。
     *
     * @param pool 当前赛季的全部合法卡名
     */
    static String normalize(String raw, java.util.Collection<String> targets, java.util.Collection<String> pool) {
        if (raw == null || raw.isEmpty()) return raw;
        if (targets.contains(raw)) return raw;
        // OCR 读到的本来就是一张合法存在的卡 —— 那不是错字，是另一张卡，绝不能再改写。
        if (pool.contains(raw)) return raw;
        for (String candidate : targets) {
            // 长度差超过 1 的不可能是同一个卡名，先剪掉大部分候选。
            if (Math.abs(candidate.length() - raw.length()) > 1) continue;
            if (distance(raw, candidate) <= 1) return candidate;
        }
        return raw;
    }
    /** 允许一次替换/插入/删除的编辑距离，只判断是否在 limit 以内。 */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev; prev = cur; cur = swap;
        }
        return prev[b.length()];
    }
    /** 需要自行回收位图的调用方（阵容头像识别）使用独立流，避免破坏共享缓存。 */
    InputStream portraitStream(String id) throws IOException { return assets.open(portraitDir + "/" + id + ".png"); }
    /** 头像按需解码并复用；缺失时返回 null，界面自行回退到首字。 */
    Bitmap portrait(String id) {
        synchronized (portraits) {
            if (portraits.containsKey(id)) return portraits.get(id);
            Bitmap bitmap = null;
            try (InputStream in = portraitStream(id)) { bitmap = BitmapFactory.decodeStream(in); } catch (IOException ignored) {}
            portraits.put(id, bitmap);
            return bitmap;
        }
    }
}
