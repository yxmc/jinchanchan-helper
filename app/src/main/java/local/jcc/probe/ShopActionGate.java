package local.jcc.probe;

/**
 * 主线程闸门：同一时刻只允许一套动作，动作结束后等画面能稳定再取帧。
 *
 * <p>买卡时一次识别会连发多个独立手势（卡间 50~100ms），期间必须整体占用闸门，
 * 否则取帧会和连点抢通道；因此用 {@link #beginSequence}/{@link #finishSequence}
 * 把整段连点包起来，而不是逐下手势进出。
 *
 * <p>**闸门必须能自愈**：占用方一旦漏了释放（例如手势被系统取消后回调路径没走完），
 * 识别循环会在 {@code delay()<0} 处直接 return，从此再也不取帧——表现为「突然不动了」，
 * 而且重新点开启也救不回来，只能重启服务。因此这里记录占用时刻，
 * 超过 {@link #MAX_HOLD_MS} 仍未释放就判定为泄漏并强行放行。
 */
final class ShopActionGate {
    /** 一次动作（含连点与结算等待）的合理上限；超过即视为泄漏。 */
    private static final long MAX_HOLD_MS = 3000;
    private long sequence, notBefore, heldSince;
    private boolean inFlight;
    private BuyPolicy.Kind kind;

    long delay(long now) {
        if (!inFlight) return Math.max(0, notBefore - now);
        // 泄漏自愈：占用方没能在合理时间内结束，说明回调路径没走完，强行放行。
        if (now - heldSince > MAX_HOLD_MS) { inFlight = false; notBefore = now; return 0; }
        return -1;
    }

    long begin(BuyPolicy.Kind action, long now) {
        if (delay(now) != 0) throw new IllegalStateException("上一个动作尚未结束");
        kind = action; inFlight = true; heldSince = now; return ++sequence;
    }

    boolean finish(long id, long now) {
        if (!inFlight || id != sequence) return false;
        inFlight = false; notBefore = now + (kind == BuyPolicy.Kind.REFRESH ? 350 : 100); return true;
    }

    /** 开始一段连点序列，返回序列号；整段结束前闸门保持占用。 */
    long beginSequence(BuyPolicy.Kind action, long now) {
        if (delay(now) != 0) throw new IllegalStateException("上一个动作尚未结束");
        kind = action; inFlight = true; heldSince = now; return ++sequence;
    }

    /** 结束连点序列；id 不匹配说明已被更新的序列接管，直接忽略。 */
    boolean finishSequence(long id, long now) {
        if (!inFlight || id != sequence) return false;
        inFlight = false; notBefore = now + (kind == BuyPolicy.Kind.REFRESH ? 350 : 100); return true;
    }

    /** 无条件释放（手势被取消/超时等没有正常结束的路径），不再追加冷却等待。 */
    boolean abort(long now) {
        if (!inFlight) return false;
        inFlight = false; notBefore = now; return true;
    }
}
