package local.jcc.probe;

import java.util.*;

/**
 * 阵容行头像匹配：取头像内部 RGB 网格描迹与棋子目录头像逐一比距离。
 *
 * <p>取样窗口固定为「内缩 8%~92%、15%~85%」，不同机型只换取样框尺寸与卡位间距
 * （小米 14 108×108 / 间距 154，K80 116×116 / 间距 164）。实测 K80 用 108 的框
 * 会把卡片左侧边框与星标带进采样，三行 27 张里只有 12 张过门限；换成 116 的方形框
 * 后升到 24 张，且 27 张首选全部正确。
 */
final class PortraitMatcher {
    private static final int GRID=12;
    /**
     * 粗搜窗口在 {@code avatarProbe} 基础上左右各放宽的像素。
     *
     * <p>**为什么不能只给一个小窗口**：`avatarProbe` 是拿标定用的那张参考截图量出来的，
     * 而真机实际渲染的阵容面板与那张图并不重合——实测两张原生 2772×1280 截图里，
     * 面板宽度都是 1923px、卡片间距都是 164px，但首卡左沿一张在 429、另一张在 485，
     * 整体平移约 47px。原来 60px 宽的窗口正好整段落在卡片左边，粗搜只能贴着窗口左边界
     * 返回一个高距离的假结果，于是三行全部「首卡未过门限」，头像名单永远是空的。
     *
     * <p>**右边也要放宽到 350**：10 个英雄的阵容会先向左滑一格再读第二次，此时首卡
     * 已经被推到 x≈586（未滑动时是 485），右边界只放 80 就完全够不着，粗搜只能在左侧
     * 暗区里挑个假匹配。窗口放宽到 190~820 才能同时覆盖「未滑动」和「滑动后」两种位置。
     *
     * <p>窗口放大后靠 {@code MIN_VARIANCE} 与 {@code MIN_PROBE_MARGIN} 两道门过滤伪匹配，
     * 这两个判据比窗口范围更可靠（见各自注释）。
     */
    private static final int PROBE_PAD_LEFT=220, PROBE_PAD_RIGHT=350, PROBE_PAD_Y=40;
    /** 粗搜步长。精调半径等于步长，保证 ±1 像素的落点一定够得到。 */
    private static final int PROBE_STEP=8;
    /**
     * 描迹方差门限。低于此值视为「平的一片」，即空位或面板外背景。
     *
     * <p>真实卡片因头像有明确明暗结构，方差在 33~80；面板外深色背景只有 18~22。
     */
    private static final double MIN_VARIANCE=26;
    /**
     * 粗搜阶段的区分度门限（最优卡与次优卡的 RMS 差）。
     *
     * <p>**这是过滤左区伪匹配的关键判据**：阵容面板左侧是羁绊栏和棋盘，那些地方取到的
     * 描迹与某张暗色头像偶然接近，RMS 能低到 38，但「次优卡也很像」——实测 margin 只有
     * 7~10。而真正的卡片 margin 最低也有 15.8（6 个真卡样本区间 15.8~49）。在 12 处切开
     * 能干净分离，比 distance 判据可靠得多。
     */
    private static final double MIN_PROBE_MARGIN=12;
    static final class Reference {
        final String id, name; final int[] values;
        Reference(String id,String name,int[] values) { this.id=id;this.name=name;this.values=values; }
    }
    static final class Match {
        String name=""; double distance=Double.MAX_VALUE, margin, variance; int x,y;
        boolean accepted() { return distance <= 58 && margin >= 12; }
    }
    static final class Row {
        final List<String> names=new ArrayList<>();
        int unknown, count, x,y;
        /**
         * 这一行是否读满了版式允许的格数。
         *
         * <p>版式一行排 9 格，阵容上限 10 个英雄：真有 10 个时第 10 张会停在面板右边界外，
         * 只露出约 10px 的左边框（实测未滑动帧里巴德仅剩一条绿边），取样窗口放不进这么窄
         * 的区域，也就无从比对。调用方据此**滑一次试试**。
         *
         * <p>**注意「读满 9 格」并不表示还有第 10 张**：绝大多数阵容就是 9 个英雄，
         * 此时行内容正好填满、不可滚动，滑动会被游戏忽略。所以真正区分有无第 10 张的
         * 判据是「滑动后首卡位置有没有变」（见 {@code LineupImporter}），不是这个标记本身。
         */
        boolean moreRight;
        /** 一次读取的关键中间量，仅用于诊断日志：哪一步把这一行拦掉了。 */
        String diagnostic="";
        /**
         * 本次读取是否把这一行认全了。
         *
         * <p>**不看 `moreRight`**：版式一行就 9 格，读满 9 格是绝大多数阵容的正常结果，
         * 拿它当「没读全」会让几乎所有导入都被判定为不完整。是否还有第 10 张由调用方
         * 结合滑动次数判断（见 {@code LineupImporter.mergeHeroes}）。
         */
        boolean complete() { return count>0 && unknown==0; }
    }
    static int[] descriptor(int[] pixels,int imageWidth,int x,int y,int width,int height) {
        int[] out=new int[GRID*GRID*3]; int k=0;
        for(int j=0;j<GRID;j++) for(int i=0;i<GRID;i++) {
            int px=x+(int)((.08+.84*(i+.5)/GRID)*width), py=y+(int)((.15+.70*(j+.5)/GRID)*height);
            int c=pixels[py*imageWidth+px]; out[k++]=(c>>16)&255;out[k++]=(c>>8)&255;out[k++]=c&255;
        }
        return out;
    }
    private static Match match(int[] value,List<Reference> references,int x,int y) {
        Match result=new Match();result.x=x;result.y=y;double second=Double.MAX_VALUE;
        double[] mean=new double[3];for(int i=0;i<value.length;i++)mean[i%3]+=value[i]/(double)(GRID*GRID);
        double variance=0;for(int i=0;i<value.length;i++){double d=value[i]-mean[i%3];variance+=d*d;}
        result.variance=Math.sqrt(variance/value.length);
        for(Reference r:references) {
            double sum=0;for(int i=0;i<value.length;i++){double d=value[i]-r.values[i];sum+=d*d;}
            double distance=Math.sqrt(sum/value.length);
            if(distance<result.distance){second=result.distance;result.distance=distance;result.name=r.name;}
            else second=Math.min(second,distance);
        }
        result.margin=second-result.distance;return result;
    }
    /**
     * 读取某一行收藏阵容的英雄头像，返回按出现顺序去重后的卡名。
     *
     * <p>先在 {@code avatarProbe} 放宽后的窗口里粗搜第一张卡的落点（不是直接相信
     * {@code avatar} 里的固定坐标，实测真机与标定截图差 47px），再向左回溯确认它真是
     * 首卡，最后按卡位间距横向推进；每格允许 ±4 像素微调。方差不足说明该格是空位，
     * 立即结束本行。
     *
     * <p>只在阵容列表页调用：收藏上限弹窗页的 `menu` 为假，`LineupVision` 不会读头像，
     * 那页的卡片网格（K80 上首卡右移 39、行距 308）因此无需在此处理。
     */
    static Row readRow(int[] pixels,int width,LineupLayout.Spec spec,int rowIndex,List<Reference> refs) {
        Row row=new Row(); Match first=null;
        int[] probe=spec.avatarProbe(rowIndex), box=spec.avatar(rowIndex);
        int side=box[2], height=box[3];
        if(refs.isEmpty()) { row.diagnostic="头像库为空"; return row; }
        // 粗搜窗口左右各放宽：标定用的参考截图与真机实际渲染的位置不重合（实测差 47px），
        // 固定窗口必然在某个位置上完全落空。
        int px0=probe[0]-PROBE_PAD_LEFT, px1=probe[2]+PROBE_PAD_RIGHT;
        int py0=probe[1]-PROBE_PAD_Y, py1=probe[3]+PROBE_PAD_Y;
        for(int y=py0;y<=py1;y+=PROBE_STEP) for(int x=px0;x<=px1;x+=PROBE_STEP) {
            if(y<0||y+height>pixels.length/width||x<0||x+side>width)continue;
            Match m=match(descriptor(pixels,width,x,y,side,height),refs,x,y);
            // 方差门限在粗搜阶段就要用：窗口放宽后左边界会扫到面板外的暗色背景，
            // 那里的描迹几乎是平的。**区分度（margin）也要一起用**——暗区取到的描迹
            // 与某张暗色头像偶然接近时 distance 能低到 38，但次优卡同样像，margin 只有
            // 7~10；真卡的 margin 最低 15.8。只看 distance 会把空背景当成首卡。
            if(m.variance<MIN_VARIANCE||m.margin<MIN_PROBE_MARGIN)continue;
            if(first==null || m.distance<first.distance)first=m;
        }
        if(first==null){ row.diagnostic="粗搜无候选"; return row; }
        row.diagnostic="粗搜 d="+(int)first.distance+" m="+(int)first.margin+" ("+first.x+","+first.y+")";
        int baseX=first.x,baseY=first.y;
        // 精调半径要盖住粗搜步长的一半，否则 8px 步长的最优格可能永远够不到门限。
        for(int y=baseY-PROBE_STEP;y<=baseY+PROBE_STEP;y++) for(int x=baseX-PROBE_STEP;x<=baseX+PROBE_STEP;x++) {
            if(y<0||y+height>pixels.length/width||x<0||x+side>width)continue;
            Match m=match(descriptor(pixels,width,x,y,side,height),refs,x,y);
            if(m.distance<first.distance)first=m;
        }
        // 向左回溯到真正的首卡：窗口放宽后，粗搜可能直接落在第 2、第 3 格上。
        // 判据是「左边一格也是一张能认出来的卡」——只要它过门限，当前这张就不是首卡。
        // **必须走到底**：错一格会让整份英雄名单整体少读一张，比读不到更麻烦。
        for(int guard=0;guard<3;guard++) {
            int leftX=first.x-spec.avatarPitch;
            if(leftX<0)break;
            Match left=null;
            for(int dy=-4;dy<=4;dy+=2)for(int dx=-4;dx<=4;dx+=2) {
                if(first.y+dy<0||leftX+dx<0||leftX+dx+side>width)continue;
                Match m=match(descriptor(pixels,width,leftX+dx,first.y+dy,side,height),refs,leftX+dx,first.y+dy);
                if(left==null||m.distance<left.distance)left=m;
            }
            if(left==null||!left.accepted())break;
            first=left;
        }
        if(!first.accepted())return row;
        row.x=first.x;row.y=first.y;
        row.diagnostic="首卡 "+first.name+" d="+(int)first.distance+" m="+(int)first.margin+" ("+first.x+","+first.y+")";
        // 行尾边界同样要放宽：面板右移后第 9 格会被固定右沿截掉，阵容里最多的 9 个英雄
        // 就会少读最后一个。
        int rightLimit=spec.avatarRight+PROBE_PAD_RIGHT;
        for(int slot=0;slot<spec.avatarSlots;slot++) {
            int x=row.x+slot*spec.avatarPitch;
            if(x+side>rightLimit||x+side>width)break;
            Match best=null;
            for(int dy=-4;dy<=4;dy+=2)for(int dx=-4;dx<=4;dx+=2) {
                if(row.y+dy<0||x+dx<0||x+dx+side>width)continue;
                Match m=match(descriptor(pixels,width,x+dx,row.y+dy,side,height),refs,x+dx,row.y+dy);
                if(best==null || m.distance<best.distance)best=m;
            }
            if(best==null)break;
            if(best.variance<MIN_VARIANCE){row.diagnostic+=" 第"+slot+"格 方差"+String.format(java.util.Locale.US,"%.0f",best.variance)+"过低(判为空位)";break;}
            row.count++;
            if(best.accepted()) { if(!row.names.contains(best.name))row.names.add(best.name); }
            else row.unknown++;
        }
        // 读满格数但没有读到空位：卡片行可能还有一格停在面板右缘之外。
        // 阵容上限 10 个英雄而版式只排 9 格，10 张时最后一张会被裁掉，必须滑动后再读一次。
        row.moreRight=row.count>=spec.avatarSlots;
        return row;
    }
}
