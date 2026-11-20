package local.jcc.probe;

final class LineupFlow {
    enum Action { WAIT, CLOSE_EXISTING, CLOSE_SHOP, OPEN_MENU, IMPORT, REPLACE_THIRD, APPLY_TARGET, FINISH }
    enum Stage { PREPARE, WAIT_BOARD, WAIT_CLOSED, WAIT_MENU, WAIT_IMPORTED, WAIT_APPLY, WAIT_CLOSED_FINAL, DONE }
    static final class View {
        boolean shop,board,menu,replacement,targetMatches,applied,full;
        /**
         * 本机型能否「点总金币收起商店」。手机可以，平板不行。
         *
         * <p>为假时状态机**不再等商店收起**，直接点右上角菜单继续。商店在屏幕底部、
         * 菜单图标在最顶部，两者不重叠，所以这是安全的。
         */
        boolean canCloseShop=true;
    }
    private Stage stage=Stage.PREPARE;
    private int replacementReads, importedReads, verifiedReads;
    private boolean appliedConfirmed,finalShopClosing,requiresReplacement;
    /**
     * 目标行是否已被确认过。
     *
     * <p>`WAIT_APPLY` 要求连续两帧「菜单在 + 标题匹配目标」才放行，那一刻已经把
     * 「这就是要的那套阵容」钉死了。之后 `WAIT_CLOSED_FINAL` 若还要求标题，就会因为
     * 应用后重绘导致标题 OCR 整行读不出来而空等——实测 27 帧 `title=false` 却
     * `applied=true`，面板早就应用好了，白等 15 秒。
     */
    private boolean rowConfirmed;
    Stage stage(){return stage;}
    /**
     * 判定「该点某个按钮」但画面上没读到它时回退一格，让下一帧重新判定。
     *
     * <p>**没有这个回退，重试就是死路**：`WAIT_IMPORTED` 一旦返回 `REPLACE_THIRD` 就把 stage
     * 推进到 `WAIT_APPLY`，而 `WAIT_APPLY` 要求 `menu=true`；替换弹窗里 `menu=false`，于是之后
     * 每一帧都返回 `WAIT`，再也不会重新给出 `REPLACE_THIRD`。表现为「替换界面明明开着，
     * 却报没有识别到替换按钮」。`APPLY_TARGET` 同理（回退到 `WAIT_APPLY` 重新累计两帧确认）。
     */
    void rewind(Action action) {
        if(action==Action.REPLACE_THIRD){stage=Stage.WAIT_IMPORTED;replacementReads=0;}
        else if(action==Action.APPLY_TARGET){stage=Stage.WAIT_APPLY;verifiedReads=0;}
        else if(action==Action.CLOSE_EXISTING){
            // 「关闭面板」可能来自三个地方，回退点各不相同：
            // - 准备阶段发现面板已经开着 → 回到 PREPARE 重新判一次；
            // - 核对阶段发现这一行已应用 → 回到 WAIT_APPLY 重新累计两帧；
            // - 收尾阶段确认应用后关闭 → 留在 WAIT_CLOSED_FINAL，该阶段每一帧都会重发。
            if(stage==Stage.PREPARE)stage=Stage.PREPARE;
            else if(stage==Stage.WAIT_CLOSED_FINAL)appliedConfirmed=false;
            else {stage=Stage.WAIT_APPLY;verifiedReads=0;}
        }
    }
    Action next(View view) {
        switch(stage) {
            case PREPARE:
                if(view.menu||view.replacement){stage=Stage.WAIT_BOARD;return Action.CLOSE_EXISTING;}
                // **商店开着不是拦路条件**：右上角「菜单/阵容」图标在屏幕最顶部，商店在底部，
                // 两者根本不重叠，直接点图标即可。
                // 手机保留「先点金币收商店」的老行为（有该交互，收起后画面更干净）；
                // **平板没有这个交互**（实测助手连点 12 次、手动点同一位置都没反应），
                // 所以在 `canCloseShop=false` 时跳过收商店，否则会死在 `WAIT_CLOSED` 直到超时——
                // 用户看到的就是「从 app 点导入打不开阵容收藏」。
                if(view.shop&&view.canCloseShop){stage=Stage.WAIT_CLOSED;return Action.CLOSE_SHOP;}
                if(view.board){stage=Stage.WAIT_MENU;return Action.OPEN_MENU;}
                return Action.WAIT;
            case WAIT_BOARD:
                if(!view.menu&&!view.replacement){stage=Stage.PREPARE;return next(view);}return Action.WAIT;
            case WAIT_CLOSED:
                // 收商店阶段：只在手机上会进来（平板在 PREPARE 就跳过了）。
                // 商店还开着就继续重发点击——商店弹出有动画，落在动画帧上的点击会被吞掉；
                // 金币面板没有交互，重发无副作用，比干等到超时安全。
                if(!view.shop&&view.board){stage=Stage.WAIT_MENU;return Action.OPEN_MENU;}
                return view.shop&&view.canCloseShop?Action.CLOSE_SHOP:Action.WAIT;
            case WAIT_MENU:
                if(view.menu){requiresReplacement=view.full;stage=Stage.WAIT_IMPORTED;return Action.IMPORT;}return Action.WAIT;
            case WAIT_IMPORTED:
                if(view.replacement) {
                    importedReads=0;if(++replacementReads<2)return Action.WAIT;
                    // 弹窗是渐入动画：前一两帧按钮可能还没画出来，OCR 也会漏字。
                    // 这里只在连续两帧都确认「弹窗在、但替换按钮认不到」后才交给调用方判定失败，
                    // 由调用方按「按钮暂时没读到」重试，而不是立刻终止整个导入。
                    stage=Stage.WAIT_APPLY;return Action.REPLACE_THIRD;
                }
                replacementReads=0;
                if(!requiresReplacement&&view.menu&&view.targetMatches) {
                    if(++importedReads<2)return Action.WAIT;
                    stage=Stage.WAIT_APPLY;return next(view);
                }
                importedReads=0;return Action.WAIT;
            case WAIT_APPLY:
                if(!view.menu||!view.targetMatches){verifiedReads=0;return Action.WAIT;}
                if(++verifiedReads<2)return Action.WAIT;
                // 连续两帧标题都匹配，这一行的身份已经钉死，后续不必再依赖标题 OCR。
                rowConfirmed=true;
                if(view.applied){appliedConfirmed=true;stage=Stage.WAIT_CLOSED_FINAL;return Action.CLOSE_EXISTING;}
                stage=Stage.WAIT_CLOSED_FINAL;return Action.APPLY_TARGET;
            case WAIT_CLOSED_FINAL:
                // 目标行在 WAIT_APPLY 阶段已经连续两帧确认过（标题匹配 + 读满两帧），
                // 到这里**不能再要求标题**：应用成功后按钮变成「取消」、面板内容重绘，
                // 标题那一行 OCR 经常整行读不出来。实测 27 帧全是 title=false 而
                // applied=true，面板明明已经应用好了却空等 15 秒才超时。
                // 「已应用」是比标题更强的证据——它只能出现在目标行上。
                if(view.menu&&view.applied&&(view.targetMatches||rowConfirmed)){appliedConfirmed=true;return Action.CLOSE_EXISTING;}
                // 收尾同样按机型：手机先把商店收起来（画面干净），平板收不起就不等它 ——
                // 否则会在已经成功应用之后空转到超时，把成功的导入报成失败。
                if(appliedConfirmed&&view.shop&&view.canCloseShop&&!finalShopClosing){finalShopClosing=true;return Action.CLOSE_SHOP;}
                if(appliedConfirmed&&!view.menu&&!view.replacement&&(!view.shop||!view.canCloseShop)&&view.board){stage=Stage.DONE;return Action.FINISH;}
                return Action.WAIT;
            default:return Action.WAIT;
        }
    }
}
