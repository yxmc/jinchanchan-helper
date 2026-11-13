package local.jcc.probe;

/**
 * 按机型/分辨率查表的商店版式。目前支持小米 14（2670×1200）、K80 至尊版（2772×1280）
 * 与 OPPO Pad 4 Pro（3392×2400）。
 *
 * <p>各机型字号几乎相同（卡名单字高 35~36 原生像素），因此裁剪框尺寸全局固定，
 * 机型只提供位置：卡名框左沿、卡名框顶、费用框相对偏移、金币与刷新按钮框、点击点。
 * 平板版式与手机差别很大（商店在底部、金币在顶部中央、刷新在左下角），
 * 但都由同一组字段描述，识别流程无需分支。
 */
public final class PhoneLayout {
    /** 模板与裁剪框尺寸，全局固定；换机型时整批字模仍可复用。 */
    public static final int NAME_W = 274, NAME_H = 55;
    public static final int COST_W = 35, COST_H = 57;
    public static final int REFRESH_W = 107, REFRESH_H = 57;
    /** 刷新按钮字模的吻合门限；商店是否打开与偏移定位都以此为准，避免两处门限不一致。 */
    public static final double SHOP_OPEN_THRESHOLD = .88;

    /**
     * 一套版式：位置数值均为该机型原生截图像素。
     *
     * <p>费用框默认与卡名框同顶（手机版）；平板的名字条较矮、费用图标偏上，
     * 由 {@link #costDy} 额外描述纵向偏移。
     */
    public static final class Spec {
        public final String label;
        public final int width, height;
        /** 五个卡名框左沿与卡名框顶（费用框默认与卡名框同顶）。 */
        public final int[] nameX;
        public final int nameY;
        /** 费用框左沿相对卡位名字框左沿的偏移，以及相对卡名框顶的纵向偏移。 */
        public final int costDx, costDy;
        public final int[] gold, refresh;
        /** 点击卡片 X（相对卡名框左沿）与 Y；刷新按钮点击点（绝对坐标）。 */
        public final int tapCardDx, tapCardY, tapRefreshX, tapRefreshY;
        /** 字模目录；该机型缺少专用字模时停用模板快速路径。 */
        public final String templateDir;
        Spec(String label, int width, int height, int[] nameX, int nameY, int costDx, int costDy,
             int[] gold, int[] refresh, int tapCardDx, int tapCardY, int tapRefreshX, int tapRefreshY,
             String templateDir) {
            this.label = label; this.width = width; this.height = height;
            this.nameX = nameX; this.nameY = nameY; this.costDx = costDx; this.costDy = costDy;
            this.gold = gold; this.refresh = refresh;
            this.tapCardDx = tapCardDx; this.tapCardY = tapCardY;
            this.tapRefreshX = tapRefreshX; this.tapRefreshY = tapRefreshY;
            this.templateDir = templateDir;
        }
        public int[] name(int slot) { return new int[]{nameX[slot], nameY, NAME_W, NAME_H}; }
        public int[] cost(int slot) { return new int[]{nameX[slot] + costDx, nameY + costDy, COST_W, COST_H}; }
    }

    /** 小米 14：原有实测值，改造前后行为完全一致。 */
    private static final Spec XIAOMI14 = new Spec(
        "小米 14", 2670, 1200,
        new int[]{488, 918, 1348, 1777, 2207}, 489,
        350, 0,
        new int[]{2470, 1029, 112, 76}, new int[]{2444, 793, 107, 57},
        183, 313, 2497, 789,
        "templates");

    /**
     * K80 至尊版：由 2772×1280 的半分辨率截图（1386×640）逐区域实测后 ×2 换算。
     * 卡位间距 457（小米 14 为 430），比屏幕宽度缩放比例更宽，故不能等比复用小米 14 坐标。
     * 卡名框左沿 = 文字墨迹左沿 − 4；卡名框顶 = 文字墨迹顶 − 9；
     * 费用框左沿 = 数字墨迹左沿 − 3；刷新框与小米 14 采用同一框内相对位置。
     *
     * <p>字模目录 templates-k80 需用 K80 原生截图生成（字号比小米 14 大约 5~7%，
     * 直接复用小米 14 字模的相似度只有 0.54，达不到 0.88 门禁）。缺文件时自动回退。
     */
    private static final Spec K80 = new Spec(
        "K80", 2772, 1280,
        new int[]{446, 902, 1360, 1816, 2274}, 523,
        371, 0,
        // 金币框取整枚金币的内圈：右侧要容得下三位数向左生长，左侧难免压到金币图标。
        // 图标花纹被 OCR 读成前导数字（51 → 951）的问题由 ShopOcr.readGoldDigitsOnly
        // 按白像素列切分解决——数字是近白色、图标是金色，两者能可靠分开。
        // 因此这里宁可按“整枚金币”给足宽度，不靠窄框去躲图标（窄框反而会切掉首位数字，50 读成 0）。
        // 实测「50」占 x2572..2642、y1109..1155；框宽 200 可覆盖三位数。
        new int[]{2500, 1100, 200, 62}, new int[]{2531, 846, 107, 57},
        189, 347, 2584, 842,
        "templates-k80");

    /**
     * OPPO Pad 4 Pro：3392×2400 原生截图实测，版式与手机完全不同。
     * 商店在屏幕底部、卡名条在卡片下沿（y 2296..2331）、金币在顶部中央偏右、刷新在左下角。
     * 卡位间距 490（手机 430~457），费用数字紧贴卡名条右侧，纵向比卡名框顶低约 5。
     */
    private static final Spec PAD4PRO = new Spec(
        "OPPO Pad 4 Pro", 3392, 2400,
        new int[]{761, 1251, 1741, 2232, 2722}, 2287,
        371, 5,
        new int[]{1645, 1828, 173, 77}, new int[]{290, 2210, 107, 57},
        182, 2065, 345, 2238,
        "templates-pad");

    private static final Spec[] SPECS = {XIAOMI14, K80, PAD4PRO};
    private static Spec current = XIAOMI14;

    /** 由截图尺寸选版式；不支持的分辨率返回 null。 */
    public static Spec find(int width, int height) {
        for (Spec spec : SPECS) if (spec.width == width && spec.height == height) return spec;
        return null;
    }
    /** 当前识别使用的版式，默认小米 14。 */
    public static Spec current() { return current; }
    /** 每帧按实际截图切换版式；返回 false 表示该分辨率不支持。 */
    public static boolean select(int width, int height) {
        Spec spec = find(width, height);
        if (spec == null) return false;
        current = spec;
        return true;
    }
    public static boolean accepts(int width, int height) { return find(width, height) != null; }
    /** 仅小米 14 的阵容导入流程完成了坐标标定，其他机型不可进入该流程。 */
    public static boolean supportsLineup(int width, int height) { return width == XIAOMI14.width && height == XIAOMI14.height; }
    public static String unsupportedMessage() {
        StringBuilder sets = new StringBuilder();
        for (Spec spec : SPECS) {
            if (sets.length() > 0) sets.append(" 或 ");
            sets.append(spec.label).append(' ').append(spec.width).append('×').append(spec.height);
        }
        return "仅适配 " + sets + " 横屏截图";
    }

    public static int[] name(int slot) { return current().name(slot); }
    public static int[] cost(int slot) { return current().cost(slot); }
    public static int[] gold() { return current().gold; }
    public static int[] refresh() { return current().refresh; }
    /** 指定版式的裁剪框，供识别器按本帧分辨率取用，避免跨帧串用版本。 */
    public static int[] name(Spec spec, int slot) { return spec.name(slot); }
    public static int[] cost(Spec spec, int slot) { return spec.cost(slot); }
    public static int[] shift(int[] rect, int offsetX) { return new int[]{rect[0]+offsetX, rect[1], rect[2], rect[3]}; }

    public static int locateOffset(int[] pixels, int width, byte[] refreshMask) { return locateOffset(pixels, width, refreshMask, current()); }
    /**
     * 指定版式定位刷新文字的水平偏移，供识别器按本帧实际分辨率调用。
     *
     * <p>偏移只用于修正横屏方向带来的整体平移。门限必须足够严：实测 K80 上正确位置
     * 相似度为 1.000，若门限过松（曾被设为 .78），程序会在错误位置凑出一个勉强达标的
     * 分数并把偏移定成 -48——之后金币、卡名、费用等所有裁剪框都被整体挪开，
     * 金币读不出来（表现为“金币区域读取失败”），并可能点错卡。
     */
    public static int locateOffset(int[] pixels, int width, byte[] refreshMask, Spec spec) {
        // 搜索范围随屏幕宽度同比放大；两机刷新按钮都留有余量，可覆盖左右两种横屏方向。
        int range = Math.round(width * 96f / XIAOMI14.width);
        int[] base = spec.refresh;
        // 原位已经高度吻合时直接采用，不再搜索，避免在别处凑出次优解。
        if (anchorScore(pixels, width, refreshMask, base, 0) >= .90) return 0;
        double best = 0; int offset = 0;
        for (int dx = -range; dx <= range; dx += 2) {
            double score = anchorScore(pixels, width, refreshMask, base, dx);
            if (score > best) { best = score; offset = dx; }
        }
        int coarse = offset;
        for (int dx = coarse - 1; dx <= coarse + 1; dx++) {
            double score = anchorScore(pixels, width, refreshMask, base, dx);
            if (score > best) { best = score; offset = dx; }
        }
        // 与商店判定使用同一套门限：搜到的位置必须真正吻合才采信，否则退回零偏移，
        // 宁可让本帧按标定坐标识别，也不要把整套裁剪框挪到错的地方。
        return best >= SHOP_OPEN_THRESHOLD ? offset : 0;
    }
    private static double anchorScore(int[] pixels, int width, byte[] reference, int[] rect, int dx) {
        byte[] sample = ScreenGuard.crop(pixels, width, shift(rect, dx));
        int ink = 0, both = 0;
        for (int i = 0; i < sample.length; i++) { ink += sample[i] + reference[i]; both += sample[i] * reference[i]; }
        return ink < 120 ? 0 : 2d * both / ink;
    }
}
