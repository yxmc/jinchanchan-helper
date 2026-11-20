package local.jcc.probe;

import java.util.*;

/**
 * 卡名容错校正回归：`ChampionCatalog.normalize()` 必须「补回真错字」且「不改写合法卡名」。
 *
 * <p>历史 bug：目标名单里有「金克丝」时，OCR 正确读出的「拉克丝」只差一个字，
 * 被当成 OCR 错字改写成「金克丝」，于是把 4 费的拉克丝当目标卡买掉。
 * 同理目标里有「沃里克」时「塔里克」「约里克」也会被改写，目标里有单字「蔚」时
 * 任何单字卡名（慎/彗/烬）都会被改写。判据是：**真 OCR 错字不在棋池里，合法卡名在**。
 */
public class NormalizeCheck {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        // S16 池子里的真实卡名（只取回归用到的那些），外加互相不成近邻的干扰项。
        Set<String> pool = new HashSet<>(Arrays.asList(
            "金克丝", "拉克丝", "沃里克", "约里克", "塔里克", "蔚", "慎", "彗", "烬",
            "德莱厄斯", "德莱文", "科加斯", "波比", "璐璐", "蒙多医生", "辛吉德",
            "布里茨", "卢锡安与赛娜"));
        List<String> targets = Arrays.asList(
            "德莱厄斯", "蔚", "沃里克", "布里茨", "蒙多医生", "辛吉德",
            "金克丝", "德莱文", "卢锡安与赛娜");

        // ① 合法卡名绝不能被改写 —— 这是误买其它卡的根因。
        for (String real : pool) {
            if (targets.contains(real)) continue;
            String got = ChampionCatalog.normalize(real, targets, pool);
            check(got.equals(real), "合法卡名被改写：" + real + " -> " + got);
        }
        // 点名复现用户报的那一例。
        check(ChampionCatalog.normalize("拉克丝", targets, pool).equals("拉克丝"), "拉克丝被改成金克丝");

        // ② 不在棋池里的 OCR 错字仍要能收敛回目标，否则会漏买。
        String[][] typos = {{"料加斯", "科加斯"}, {"破比", "波比"}, {"德菜文", "德莱文"}, {"璐路", "璐璐"}};
        List<String> withReal = new ArrayList<>(targets);
        withReal.add("科加斯"); withReal.add("波比"); withReal.add("璐璐");
        for (String[] pair : typos) {
            String got = ChampionCatalog.normalize(pair[0], withReal, pool);
            check(got.equals(pair[1]), "OCR 错字未能收敛：" + pair[0] + " -> " + got + "（期望 " + pair[1] + "）");
        }

        // ③ 空值与无法收敛的情况原样返回。
        check(ChampionCatalog.normalize("", targets, pool).isEmpty(), "空串被改动");
        check(ChampionCatalog.normalize(null, targets, pool) == null, "null 被改动");
        check(ChampionCatalog.normalize("完全无关的名字", targets, pool).equals("完全无关的名字"), "无关名字被改动");

        System.out.println("Normalize checks passed.");
    }
}
