package local.jcc.probe;

import java.util.*;

/** Pure state machine. A decision never counts a purchase until the next frame confirms it. */
public final class BuyPolicy {
    public enum Kind { BUY, REFRESH, WAIT, STOP }
    public static final class Frame {
        public boolean layoutValid, shopOpen, readable;
        public int gold = -1;
        public int offsetX;
        /** 本帧截图的原生尺寸，点击阶段据此选用同一套版式。 */
        public int width, height;
        public final String[] names = new String[5];
        public final int[] costs = new int[5];
        public String problem = "";
        public String summary() { return Arrays.toString(names) + " · 金币 " + gold; }
    }
    public static final class Decision {
        public final Kind kind;
        public final int slot;
        public final int[] slots;
        public final String message;
        Decision(Kind kind, int slot, String message) {
            this.kind = kind; this.slot = slot; this.message = message;
            this.slots = kind == Kind.BUY ? new int[]{slot} : new int[0];
        }
        Decision(int[] slots, String message) { this.kind = Kind.BUY; this.slots = slots; this.slot = slots[0]; this.message = message; }
    }
    private final List<String> targets;
    private int reserve;
    private boolean autoRefresh;
    private Decision pending;
    private int previousGold, misses;
    private String[] previousNames;
    private String[] stableNames;
    private int[] stableCosts;
    private int stableReads, stableOffset;
    private boolean refreshTransition;
    /** 连续多少帧看不到商店按钮才认定玩家真的离开了商店（宽限期内保留卡面稳定度等状态）。 */
    private static final int CLOSED_GRACE_FRAMES = 4;
    private int closedFrames;
    public int bought, refreshed;

    public BuyPolicy(List<String> targets, int reserve, boolean autoRefresh) {
        if (targets.isEmpty() || reserve < 0) throw new IllegalArgumentException();
        this.targets = new ArrayList<>(targets); this.reserve = reserve; this.autoRefresh = autoRefresh;
    }
    public Decision decide(Frame frame) {
        if (!frame.layoutValid) return decision(Kind.WAIT, -1, "等待匹配的横屏画面");
        if (!frame.shopOpen) {
            // 刷新动画、选强挑战、装备选择器等界面都会让刷新按钮消失，但它们不是“商店关闭”。
            // 这些界面短暂出现时若清空全部状态，切回商店后刷新门槛要从头累积，
            // 表现为明明开着自动刷新却迟迟不 D 牌。因此保留一段宽限期：
            // 只有连续多帧都看不到商店，才认定玩家真的离开了商店。
            if (pending != null && pending.kind == Kind.REFRESH) { stableReads = 0; stableNames = null; stableCosts = null; }
            else if (++closedFrames > CLOSED_GRACE_FRAMES) shopClosed();
            return decision(Kind.WAIT, -1, "已开启 · 等待打开商店");
        }
        closedFrames = 0;
        observe(frame);
        if (frame.gold < 0)
            return decision(Kind.WAIT, -1,
                (frame.problem.isEmpty() ? "当前帧暂未读清" : frame.problem) + "，继续识别");
        if (pending != null) {
            if (pending.kind == Kind.REFRESH) {
                if (Arrays.stream(frame.names).allMatch(name -> "".equals(name))) refreshTransition = true;
                boolean full = frame.readable && Arrays.stream(frame.names).allMatch(name -> name != null && !name.isEmpty());
                if (!full || stableReads < 2 || (!refreshTransition && Arrays.equals(frame.names, previousNames)))
                    return decision(Kind.WAIT, -1, "等待刷新后的新卡面稳定");
                refreshed++; pending = null; misses = 0;
            }
        }
        if (pending != null) {
            int completed = 0;
            for (int slot : pending.slots) if ("".equals(frame.names[slot])) completed++;
            if (completed > 0) {
                bought += completed;
                pending = null; misses = 0;
            } else if (!Arrays.equals(frame.names, previousNames)) {
                // 卡面变了但目标格没变空：这一次点击没生效（实测同一手势里多张卡的
                // 后续点击会被游戏忽略）。不计数，但要让下一次决策重新规划再点一次。
                pending = null; misses = 0;
            } else if (frame.readable && ++misses >= (frame.gold == previousGold ? 2 : 3)) {
                // Income and imperfect gold OCR must not keep a completed gesture pending forever.
                // Retry only by planning from this fresh frame; never count an unconfirmed purchase.
                pending = null; misses = 0;
            } else return decision(Kind.WAIT, -1, "等待" + (pending.kind == Kind.BUY ? "购买" : "刷新") + "结果");
        }
        List<Integer> slots = new ArrayList<>();
        int balance = frame.gold;
        boolean targetPresent = false, unknownCost = false;
        for (int i = 0; i < 5; i++) if (targets.contains(frame.names[i])) {
            targetPresent = true;
            int cost = frame.costs[i];
            if (cost < 1 || cost > 10) { unknownCost = true; continue; }
            if (balance < cost) continue;
            slots.add(i); balance -= cost;
        }
        if (!slots.isEmpty()) return batch(slots);
        if (targetPresent) return decision(Kind.WAIT, -1, unknownCost ? "等待读取目标费用" : "金币不足以购买目标卡");
        if (!frame.readable) return decision(Kind.WAIT, -1,
            (frame.problem.isEmpty() ? "当前帧暂未读清" : frame.problem) + "，继续识别");
        if (!autoRefresh) return decision(Kind.WAIT, -1, "当前没有目标，等待你手动刷新");
        if (frame.gold - 2 < reserve) return decision(Kind.WAIT, -1, "等待金币达到刷新条件");
        if (stableReads < 2) return decision(Kind.WAIT, -1, "等待商店卡面稳定再刷新");
        return decision(Kind.REFRESH, -1, "没有目标，刷新商店");
    }
    /**
     * 记录本次实际发出的动作。一次手势只点一张卡（同帧多张的后续点击会被游戏忽略），
     * 因此 BUY 只按第一个格子的点击结果确认，其余格子留给下一帧重新规划再买。
     */
    public void sent(Decision action, Frame frame) {
        if (action.kind != Kind.BUY && action.kind != Kind.REFRESH) throw new IllegalArgumentException();
        pending = action.kind == Kind.BUY ? new Decision(new int[]{action.slots[0]}, action.message) : action;
        previousGold = frame.gold;
        previousNames = frame.names.clone();
        misses = 0; refreshTransition = false;
        stableReads = 0; stableNames = null; stableCosts = null;
    }
    private void observe(Frame frame) {
        // 金币读不出时无从判断画面是否稳定；其余情况都参与比较，但容忍 OCR 抖动。
        if (frame.gold < 0) { stableReads = 0; stableNames = null; stableCosts = null; return; }
        if (stableNames != null && frame.offsetX == stableOffset && sameShop(frame)) stableReads++;
        else { stableReads = 1; stableNames = frame.names.clone(); stableCosts = frame.costs.clone(); stableOffset = frame.offsetX; }
    }
    /**
     * 判断两帧是否同一个商店画面。
     *
     * <p>不能要求卡名逐字节全等：OCR 每帧都有抖动（同一张卡写成「可酷伯」/「可醋伯」、
     * 空格时有时无、某格偶发读空），实测相邻两帧完全一致的比例只有约四分之一。
     * 刷新门槛若要求全等，绝大多数时候凑不出「连续两帧稳定」，
     * 表现就是明明开着自动刷新却几乎不 D 牌。
     *
     * <p>改为证据强度足够的近似判断：费用全部相等、五格中至少四格一致即为同一画面。
     * 真正的刷新会整排换卡，不会满足这个条件，因此不会把新卡面误当成旧卡面。
     */
    private boolean sameShop(Frame frame) {
        if (!Arrays.equals(frame.costs, stableCosts)) return false;
        int agree = 0;
        for (int i = 0; i < 5; i++) {
            String now = frame.names[i], before = stableNames[i];
            boolean nowEmpty = now == null || now.isEmpty(), beforeEmpty = before == null || before.isEmpty();
            if (nowEmpty && beforeEmpty) agree++;
            else if (!nowEmpty && !beforeEmpty && now.equals(before)) agree++;
        }
        return agree >= 4;
    }
    public void shopClosed() { pending = null; previousNames = null; misses = 0; stableReads = 0; stableNames = null; stableCosts = null; refreshTransition = false; closedFrames = 0; }
    public void setAutoRefresh(boolean enabled) { autoRefresh = enabled; }
    public void setReserve(int value) {
        if (value < 0 || value > 999) throw new IllegalArgumentException("保留金币应为 0～999");
        reserve = value;
    }
    public boolean isAutoRefresh() { return autoRefresh; }
    private static Decision batch(List<Integer> slots) {
        int[] ordered = slots.stream().mapToInt(Integer::intValue).toArray();
        return new Decision(ordered, "连续购买 " + ordered.length + " 张目标卡");
    }
    private static Decision decision(Kind kind, int slot, String message) { return new Decision(kind, slot, message); }
    public static List<String> parseTargets(String input) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : input.split("[\\s,，、;；]+")) {
            name = name.trim();
            if (!name.isEmpty()) names.add(name);
        }
        if (names.size() > 20) throw new IllegalArgumentException("最多设置 20 个目标");
        for (String name : names) if (!name.matches("[\\p{IsHan}·]{1,12}"))
            throw new IllegalArgumentException("请输入完整中文卡名：" + name);
        return new ArrayList<>(names);
    }
}
