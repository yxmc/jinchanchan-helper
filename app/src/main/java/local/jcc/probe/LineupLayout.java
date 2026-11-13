package local.jcc.probe;

/**
 * 阵容面板（游戏内「阵容」收藏列表页）的按机型查表版式。
 *
 * <p>{@link PhoneLayout} 描述的是商店页；阵容页的标题带、按钮带、卡位与行间距都与商店不同，
 * 因此单独一张表。目前登记小米 14（2670×1200）与 K80 至尊版（2772×1280）。
 *
 * <p>所有数值均为该机型原生截图像素。新增机型必须用该机型原生截图逐区域实测，
 * 不能按分辨率等比缩放他机型坐标——两机的卡位间距（154 / 164）与行间距（304 / 324）都不成比例。
 */
public final class LineupLayout {
    /** 棋盘右上阵容图标的描迹模板，三机型共用：K80 棋盘得分 32、商店页面 105，门限 55 可区分。 */
    static final String ICON_ASSET = "templates/lineup-button.bin";

    public static final class Spec {
        public final String label;
        public final int width, height;
        /** 「阵容」标题、「提示」与「导入代码」按钮的 OCR 搜索区，格式 {left, top, right, bottom}。 */
        public final int[] headerArea, promptArea, importArea;
        /** 关闭按钮点击点：横向相对标题（或提示）左沿的偏移、纵向绝对坐标。 */
        public final int closeDx, closeY;
        /** 收藏上限弹窗里「替换」按钮的搜索区。 */
        public final int[] replaceArea;
        /** 三套收藏阵容的标题带与按钮带 {top, bottom}。 */
        public final int[][] titleBands, buttonBands;
        /** 标题与「应用/取消」按钮的横向搜索范围（按 OCR 文字中心 X 判定）。 */
        public final int titleLeft, titleRight, buttonLeft, buttonRight;
        /** 行头像指纹区域 {x, y, 宽, 高}，标题 OCR 抖动时用于确认仍是同一行。 */
        public final int[] rowSignature;
        /** 相邻两行收藏阵容的纵向间距。 */
        public final int rowPitch;
        /** 头像取样框 {x, y, 宽, 高}、一行最多几张卡、卡位间距、最右可用边界。 */
        public final int[] avatar;
        public final int avatarSlots, avatarPitch, avatarRight;
        /** 头像粗搜区域 {x0, y0, x1, y1}：该行第一张卡左上角的可取值范围。 */
        public final int[] avatarProbe;
        /** 棋盘右上阵容图标的模板搜索区 {x0, y0, x1, y1, 模板边长}。 */
        public final int[] iconSearch;
        /** 判定「在棋盘上」的图标相似度门限（RMS，越小越像）；非棋盘画面会凑出 50~130 分。 */
        public final double iconThreshold;
        /** 预览裁剪的最右边界、最下边界，以及「应用」按钮半宽（用于推算按钮左沿）。 */
        public final int previewRightMax, previewBottomMax, buttonHalfWidth;
        /** 头像取样框顶到预览底沿的距离（含装备条），小米 14 为 152。 */
        public final int previewRowHeight;

        Spec(String label, int width, int height, int[] headerArea, int[] promptArea, int[] importArea,
             int closeDx, int closeY, int[] replaceArea, int[][] titleBands, int[][] buttonBands,
             int titleLeft, int titleRight, int buttonLeft, int buttonRight, int[] rowSignature, int rowPitch,
             int[] avatar, int avatarSlots, int avatarPitch, int avatarRight, int[] avatarProbe,
             int[] iconSearch, double iconThreshold, int previewRightMax, int previewBottomMax,
             int buttonHalfWidth, int previewRowHeight) {
            this.label = label; this.width = width; this.height = height;
            this.headerArea = headerArea; this.promptArea = promptArea; this.importArea = importArea;
            this.closeDx = closeDx; this.closeY = closeY; this.replaceArea = replaceArea;
            this.titleBands = titleBands; this.buttonBands = buttonBands;
            this.titleLeft = titleLeft; this.titleRight = titleRight;
            this.buttonLeft = buttonLeft; this.buttonRight = buttonRight;
            this.rowSignature = rowSignature; this.rowPitch = rowPitch;
            this.avatar = avatar; this.avatarSlots = avatarSlots;
            this.avatarPitch = avatarPitch; this.avatarRight = avatarRight;
            this.avatarProbe = avatarProbe; this.iconSearch = iconSearch;
            this.iconThreshold = iconThreshold;
            this.previewRightMax = previewRightMax; this.previewBottomMax = previewBottomMax;
            this.buttonHalfWidth = buttonHalfWidth;
            this.previewRowHeight = previewRowHeight;
        }
        public int[] titleBand(int row) { return titleBands[row]; }
        public int[] buttonBand(int row) { return buttonBands[row]; }
        /** 该行头像取样框。 */
        public int[] avatar(int row) { return new int[]{avatar[0], avatar[1] + row * rowPitch, avatar[2], avatar[3]}; }
        /** 该行头像粗搜区域（左上角可取值范围）。 */
        public int[] avatarProbe(int row) { return new int[]{avatarProbe[0], avatarProbe[1] + row * rowPitch, avatarProbe[2], avatarProbe[3] + row * rowPitch}; }
        /** 该行头像指纹区域。 */
        public int[] rowSignature(int row) { return new int[]{rowSignature[0], rowSignature[1] + row * rowPitch, rowSignature[2], rowSignature[3]}; }
    }

    /** 小米 14：原有实测值，改造前后行为完全一致。 */
    private static final Spec XIAOMI14 = new Spec(
        "小米 14", 2670, 1200,
        new int[]{400, 75, 850, 230}, new int[]{400, 75, 1700, 230}, new int[]{1200, 75, 1750, 240},
        1690, 157, new int[]{1900, 820, 2310, 1140},
        new int[][]{{210, 325}, {510, 640}, {810, 940}}, new int[][]{{220, 500}, {510, 810}, {820, 1140}},
        480, 1950, 1900, 2310,
        new int[]{535, 335, 1350, 90}, 304,
        new int[]{480, 290, 108, 108}, 10, 154, 2035,
        // 原实现的粗搜范围是「左上角 x480..620 / y290..390，步长 6」，这里记左上角取值范围。
        new int[]{480, 290, 620, 390},
        new int[]{2200, 25, 2340, 53, 64}, 55,
        2035, 1110, 100, 152);

    /**
     * K80 至尊版：由 2772×1280 原生截图逐区域实测（商店页对照见 {@link PhoneLayout#find}）。
     *
     * <p>面板底板 x473..2394、y177..1177；三行标题文字带 y254..303 / 579..627 / 903..952；
     * 三行卡牌 y342..500 / 666..825 / 991..1148；行间距 324、卡位间距 164。首卡头像
     * x536..651、y362..469，一行最多 9 张。三套「应用」按钮 x2136..2343，
     * y379..462 / 703..785 / 1027..1109。关闭 X 中心 (2335,166)，导入代码胶囊 x1409..1654。
     *
     * <p>头像取样框取 116×116 而非小米 14 的 108：实测 108 会把卡片左沿边框与星标带进
     * 采样，27 张里只有 12 张过门限；按 116 的方形框取值后 27 张首选全对且全部过门限。
     *
     * <p>棋盘右上阵容图标圆环 x2340..2469、y34..115。模板边长 82 时棋盘态得分 34.7~34.8，
     * 而商店/强化符文选卡等非棋盘画面在 x2300..2400 内会凑出 51.7~131，因此门限取 42
     * （小米 14 沿用原来的 55）。收藏上限弹窗（替换第三套）暂无 K80 截图，
     * 搜索区按面板右下角给足余量；识别不到按钮时流程会直接停止，不会误点。
     */
    private static final Spec K80 = new Spec(
        "K80", 2772, 1280,
        new int[]{430, 100, 900, 240}, new int[]{430, 100, 2000, 240}, new int[]{1300, 100, 1750, 250},
        1807, 166, new int[]{1950, 860, 2390, 1175},
        new int[][]{{230, 330}, {555, 655}, {880, 980}}, new int[][]{{355, 490}, {680, 820}, {1005, 1145}},
        480, 2150, 1950, 2390,
        new int[]{532, 350, 1440, 110}, 324,
        new int[]{532, 356, 116, 116}, 9, 164, 2136, new int[]{516, 344, 556, 366},
        new int[]{2300, 20, 2400, 70, 82}, 42,
        2130, 1140, 104, 144);

    private static final Spec[] SPECS = {XIAOMI14, K80};

    /** 由截图尺寸选版式；未标定的分辨率返回 null。 */
    public static Spec find(int width, int height) {
        for (Spec spec : SPECS) if (spec.width == width && spec.height == height) return spec;
        return null;
    }
    /** 阵容导入是否支持该分辨率（横竖两种朝向都接受）。 */
    public static boolean supports(int width, int height) {
        return find(width, height) != null || find(height, width) != null;
    }
    /** 已标定机型的提示文案，用于不支持时的错误提示。 */
    public static String unsupportedMessage() {
        StringBuilder sets = new StringBuilder();
        for (Spec spec : SPECS) {
            if (sets.length() > 0) sets.append(" 或 ");
            sets.append(spec.label).append(' ').append(spec.width).append('×').append(spec.height);
        }
        return "阵容导入仅支持已在 " + sets + " 上标定的机型";
    }
}
