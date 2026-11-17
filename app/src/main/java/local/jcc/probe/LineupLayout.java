package local.jcc.probe;

/**
 * 阵容面板（游戏内「阵容」收藏列表页）的按机型查表版式。
 *
 * <p>{@link PhoneLayout} 描述的是商店页；阵容页的标题带、按钮带、卡位与行间距都与商店不同，
 * 因此单独一张表。目前登记小米 14（2670×1200）、K80 至尊版（2772×1280）
 * 与 OPPO Pad 4 Pro（3392×2400）。
 *
 * <p>所有数值均为该机型原生截图像素。新增机型必须用该机型原生截图逐区域实测，
 * 不能按分辨率等比缩放他机型坐标——两机的卡位间距（154 / 164）与行间距（304 / 324）都不成比例。
 */
public final class LineupLayout {
    /**
     * 棋盘右上阵容图标的描迹模板（12×12×3=432 字节）。手机机型共用这一份；
     * 平板用自己目录下的 `lineup-button.bin`（见 {@link Spec#iconAsset}）。
     *
     * <p>**必须按机型分**：手机上是独立的「阵容」图标，而平板（OPPO Pad 4 Pro）右上只有
     * 「菜单」与「设置」两个圆形图标、形状完全不同。用手机模板去匹配平板图标，
     * RMS 必然远高于门限，`formationIcon` 恒为 null，导入流程会一直卡在「等待阵容列表」
     * 而永远点不到菜单——这正是平板上「打开收藏阵容但后续不导入」的原因。
     */
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
        /** 该机型的阵容入口图标描迹模板；不同机型图标形状不同，不能共用。 */
        public final String iconAsset;

        Spec(String label, int width, int height, int[] headerArea, int[] promptArea, int[] importArea,
             int closeDx, int closeY, int[] replaceArea, int[][] titleBands, int[][] buttonBands,
             int titleLeft, int titleRight, int buttonLeft, int buttonRight, int[] rowSignature, int rowPitch,
             int[] avatar, int avatarSlots, int avatarPitch, int avatarRight, int[] avatarProbe,
             int[] iconSearch, double iconThreshold, int previewRightMax, int previewBottomMax,
             int buttonHalfWidth, int previewRowHeight) {
            this(label, width, height, headerArea, promptArea, importArea, closeDx, closeY, replaceArea,
                titleBands, buttonBands, titleLeft, titleRight, buttonLeft, buttonRight, rowSignature, rowPitch,
                avatar, avatarSlots, avatarPitch, avatarRight, avatarProbe, iconSearch, iconThreshold,
                previewRightMax, previewBottomMax, buttonHalfWidth, previewRowHeight, ICON_ASSET);
        }

        Spec(String label, int width, int height, int[] headerArea, int[] promptArea, int[] importArea,
             int closeDx, int closeY, int[] replaceArea, int[][] titleBands, int[][] buttonBands,
             int titleLeft, int titleRight, int buttonLeft, int buttonRight, int[] rowSignature, int rowPitch,
             int[] avatar, int avatarSlots, int avatarPitch, int avatarRight, int[] avatarProbe,
             int[] iconSearch, double iconThreshold, int previewRightMax, int previewBottomMax,
             int buttonHalfWidth, int previewRowHeight, String iconAsset) {
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
            this.iconAsset = iconAsset;
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
     * K80 至尊版：**全部数值由 2772×1280 原生截图重新实测**（v0.11.27）。
     *
     * <p>旧值来自半分辨率截图（1386×640）目测后 ×2 换算，与原生截图的真实落点整体偏右约
     * 96 像素——上一版把面板左沿记成 473、应用按钮记成 x2136..2343，而原生图实测分别是
     * 370 与 2037..2251。偏移量在整屏 HUD（羁绊面板、顶部回合条）上也是同一个 96，
     * 说明是标定基准错，不是游戏改版；因此行间距 324、卡位间距 164 与旧值一致，只有横向
     * 位置需要整套左移。
     *
     * <p>原生实测：面板金框 x370..2306 / y94..1184；三行标题文字 y259..296 / 583..620 /
     * 908..944；三行卡牌 y337..503 / 662..828 / 986..1152；行间距 324、卡位间距 164；
     * 首卡头像左上 (436,357)、116×116、一行 9 张（第 10 张被面板右沿裁掉）。
     * 三套「应用」按钮 x2037..2251，y372..465 / 697..788 / 1021..1112（中心 x2144）。
     * 关闭 X 中心 (2229,167)；「导入代码」文字 x1342..1529、下载图标 x1309..1330；
     * 「游戏资料」文字 x1631..1819、「更多阵容」x1920..2111。
     *
     * <p>收藏上限弹窗（「替换第三套」）这次拿到了原生截图，并且发现它与列表页**不是同一套
     * 网格**：三行「替换」按钮中心 y383 / 691 / 998（行距 307，列表页是 324），
     * x2046..2307；三行标题 y265..301 / 572..609 / 880..915（列表页 259..296 / 583..620 /
     * 908..944）。所以标题带与按钮带都按**两页并集**给，保证同一套字段在两页都只命中本行。
     *
     * <p>注意：弹窗页**不读取头像**——`LineupVision` 只在 `menu` 为真时调 `readRow`，而弹窗页
     * `menu` 为假。弹窗页要认的是「替换」按钮，头像网格仍只按列表页标定。
     * `replaceArea` 只框第三行（y930..1075），第三行与第一行差 615 像素，不会选错。
     *
     * <p>头像取样框 116×116：实测 108 会把卡片左沿边框与星标带进采样，27 张里只有 12 张
     * 过门限；116 方形框下三行 27 张全部命中且 margin 稳定（左沿 435..437 / 顶 355..359
     * 是同一片平台区，取中间值）。
     *
     * <p>棋盘右上阵容图标圆盘 x2293..2372 / y34..115。模板边长 82 时棋盘态得分 33.4，
     * 而阵容列表页在同一搜索区会凑出 69.4、收藏上限弹窗 102.8，因此门限取 42。
     */
    private static final Spec K80 = new Spec(
        "K80", 2772, 1280,
        // 「阵容」文字 x432..533 / y136..186；「提示」x443..545 / y141..189。
        new int[]{430, 100, 900, 240}, new int[]{400, 100, 1000, 240},
        // 导入代码：只框下载图标与「导入代码」文字（x1309..1529），右沿收到 1600
        // 以免把「游戏资料」（x1631 起）误当成导入按钮。
        new int[]{1285, 120, 1600, 215},
        // 关闭 X 中心 2229：相对「阵容」左沿 432 为 1797，相对「提示」左沿 443 为 1786，
        // 取 1790 让两者都落在 X 字形（2207..2251）内。
        1790, 167, new int[]{2000, 930, 2290, 1075},
        // 标题带取「列表页」与「收藏上限弹窗」的并集：两页同名标题分别落在
        // y259..296/583..620/908..944 与 y265..301/572..609/880..915，共用一档带即可覆盖。
        new int[][]{{250, 310}, {565, 632}, {872, 954}},
        // 按钮带取「列表页」与「收藏上限弹窗」的并集：列表页「应用」y372..465/697..788/1021..1112，
        // 弹窗「替换」y344..423/652..730/959..1037；并集后每行只含本页那一个按钮，不会串行。
        new int[][]{{336, 470}, {643, 792}, {950, 1116}},
        400, 1900, 2000, 2290,
        // 行指纹取前 9 张卡的头像横带（436 → 1864），标题 OCR 抖动时用于确认仍是同一行。
        new int[]{436, 357, 1440, 116}, 324,
        new int[]{436, 357, 116, 116}, 9, 164, 1905, new int[]{410, 342, 470, 380},
        new int[]{2280, 25, 2370, 70, 82}, 42,
        // 预览右沿收到「应用」按钮左侧（按钮左沿 2037 − 2），下沿给到第三行卡底 1152。
        2040, 1155, 107, 147);

    /**
     * OPPO Pad 4 Pro：由 3392×2400 原生截图逐区域实测（连续三帧交叉核对，排除画面闪烁干扰）。
     *
     * <p>与小米 14 / K80 的结构差异：**平板棋盘右上只有「菜单」与「设置」两个圆形图标**，
     * 点菜单先进阵容详情页、再点「返回阵容选择」才到收藏列表页；没有手机上独立阵容图标。
     *
     * <p>实测（面板底板 x278..3113、y406..1993）：
     * 「阵容」标题（x361..614）与三颗胶囊按钮同在 y466..529 一行：导入代码（文字 x1704..1979）、
     * 游戏资料（≈2129..2406）、更多阵容（≈2554..2836）；
     * 三行阵容标题文字带 y630..690 / 1118..1176 / 1596..1654（行间距 488、478，取 484）；
     * 三行「应用」按钮中心 y826 / 1308 / 1786，棕底金框 x2730..3040。
     * 卡位竖边框 x359/600/841/1083/1324/1565/1808…，**卡位间距 241**；
     * 单卡纵向 y773..963（含装备条），头像立绘区 y773..943。
     * 头像取样框 110×110、左沿 374、顶 790——实测 116 会把左边框与星标带进采样。
     *
     * <p>关闭 X 与「导入代码」是**两处最易点错的位置**，必须严格按实测值：
     * 关闭 X 中心 x≈3008 / y≈504（标题左沿 361 → closeDx 2647）；「导入代码」胶囊文字
     * x1704..1979（中心 ≈1841），而「游戏资料」在中心 ≈2333、「更多阵容」在 ≈2714——
     * 三者挨得很近，`importArea` 一旦放宽就会点成游戏资料。
     *
     * <p>收藏上限替换界面已实测：面板 x278..3113、「提示」标题 y480..530、
     * 三行「替换」按钮中心 y882 / 1335 / 1788（行间距 453、x2730..3040）。
     */
    private static final Spec PAD4PRO = new Spec(
        "OPPO Pad 4 Pro", 3392, 2400,
        new int[]{280, 420, 900, 580}, new int[]{280, 460, 1900, 560}, new int[]{1690, 460, 2000, 555},
        2647, 504, new int[]{2450, 820, 3120, 1930},
        new int[][]{{610, 710}, {1098, 1196}, {1576, 1674}},
        new int[][]{{760, 900}, {1238, 1378}, {1716, 1856}},
        380, 2200, 2700, 3120,
        new int[]{380, 790, 1600, 110}, 477,
        new int[]{374, 790, 110, 110}, 9, 241, 2600, new int[]{364, 778, 400, 806},
        new int[]{3000, 20, 3200, 180, 82}, 55,
        2600, 1990, 150, 175, "templates-pad/lineup-button.bin");

    private static final Spec[] SPECS = {XIAOMI14, K80, PAD4PRO};

    /** 由截图尺寸选版式；未标定的分辨率返回 null。 */
    public static Spec find(int width, int height) {
        for (Spec spec : SPECS) if (spec.width == width && spec.height == height) return spec;
        return null;
    }
    /**
     * 阵容导入是否支持该分辨率。
     *
     * <p>**必须与 {@link #find} 完全一致**：这里曾写成「横竖两种朝向都接受」，而 `find()` 只认
     * 精确尺寸。两者不一致就会出现「助手说机型支持、点导入却弹『仅支持已在…标定的机型』」——
     * 例如旋转过渡帧给出 1280×2772，`supports()` 判 true 放行，`find()` 返回 null 又拒绝。
     * 而且**绝不能把竖屏帧套用横屏坐标**：那会按错误的坐标系派发点击，比直接拒绝危险得多。
     * 竖屏帧应由调用方当作「还没到横屏」重试，而不是当成受支持。
     */
    public static boolean supports(int width, int height) {
        return find(width, height) != null;
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
