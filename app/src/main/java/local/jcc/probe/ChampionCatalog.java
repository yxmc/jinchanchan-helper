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
