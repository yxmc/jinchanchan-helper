package local.jcc.probe;

import java.util.*;
import java.util.regex.*;

/** Share text is data, never an executable instruction. Keep its payload opaque. */
final class LineupCode {
    private static final Pattern CODE = Pattern.compile("#([^#\\r\\n]{1,80})#([A-Za-z0-9+/_=-]{8,512})");
    final String name, payload, text;
    private LineupCode(String name, String payload) { this.name = name; this.payload = payload; text = "【阵容码】#"+name+"#"+payload; }
    static List<LineupCode> parseAll(String input) {
        if (input == null || input.length() > 131072) throw new IllegalArgumentException("阵容码内容为空或过长");
        Matcher matcher = CODE.matcher(input); LinkedHashMap<String, LineupCode> codes = new LinkedHashMap<>();
        while (matcher.find()) {
            String name = matcher.group(1).trim(), payload = matcher.group(2);
            if (name.isEmpty()) throw new IllegalArgumentException("阵容名称不能为空");
            try { if (Base64.getDecoder().decode(payload.replace('-', '+').replace('_', '/')).length == 0) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("阵容码格式不正确："+name); }
            codes.put(payload, new LineupCode(name, payload));
        }
        if (codes.isEmpty()) throw new IllegalArgumentException("没有找到有效阵容码，请粘贴完整的【阵容码】#名称#代码");
        if (codes.size() > 200) throw new IllegalArgumentException("单次最多添加 200 套阵容");
        return new ArrayList<>(codes.values());
    }
    /**
     * 阵容标题比对：判断某个读到的标题是不是阵容码里那套。
     *
     * <p>**必须容忍游戏把长标题截断**（`applied` 判定、目标行选择全靠它）。
     * 游戏列表里每行标题只显示一行宽，超长会直接截成「前面的字 + …」。
     * 实测阵容码 `【强化符文专属-暮光试炼】7德玛西亚2神盾使2法师3暗裔`（去符号 25 字）
     * 在游戏里显示成 `【强化符文专属-暮光试炼】7德玛西…`（去符号只有 14 字）。
     * 旧实现要求两侧等长，于是长名阵容**永远匹配不上**：`targetMatches` 恒假，
     * 流程死在 `WAIT_IMPORTED`/`WAIT_APPLY`，连头像都不会去读，用户看到的就是
     * 「一直卡着不动、也不左滑」。
     *
     * <p>因此：只要读到的标题是期望标题的**前缀**（≥{@link #MIN_PREFIX} 个字），就判为同一行。
     * 前缀判据是安全的——同一列表里不同阵容的标题前缀几乎不可能撞车，
     * 而「少读几个字」正是截断的唯一表现。
     */
    static boolean titleMatches(String expected,String observed) {
        String key=expected.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        String value=observed.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        // 游戏侧截断：读到的比期望短，且是期望的前缀。
        if(value.length()>=MIN_PREFIX&&value.length()<key.length()) {
            if(key.startsWith(value)) return true;
            // 截断 + 一位 OCR 抖动：允许前缀段内有一个字不同。
            if(value.length()>=MIN_PREFIX+4&&differences(key.substring(0,value.length()),value)<=1) return true;
        }
        // 期望侧被截到 16 字后再比（超长阵容名的原有行为，保持不变）。
        if(key.length()>16){key=key.substring(0,16);if(value.length()>=16)value=value.substring(0,16);}
        if(key.equals(value))return !key.isEmpty();
        if(key.length()<6||key.length()!=value.length())return false;
        return differences(key,value)<=1;
    }
    /** 前缀判定至少要读到这么多字，太少容易撞车（如「德玛西亚」「约德尔人」这类开头的其它阵容）。 */
    private static final int MIN_PREFIX=8;
    private static int differences(String a,String b) {
        int n=0;for(int i=0;i<a.length()&&i<b.length();i++)if(a.charAt(i)!=b.charAt(i))n++;
        return n+Math.abs(a.length()-b.length());
    }
    static LineupCode parse(String input) {
        List<LineupCode> list = parseAll(input);
        if (list.size() != 1) throw new IllegalArgumentException("请选择一套阵容码");
        return list.get(0);
    }
}
