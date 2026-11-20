package local.jcc.probe;

import android.content.Context;
import android.graphics.*;
import com.google.android.gms.tasks.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.*;

final class LineupVision implements AutoCloseable {
    private final TextRecognizer ocr=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    private final Context context;
    /**
     * 阵容入口图标描迹，按机型 asset 路径缓存。
     *
     * <p>**不能共用一套**：手机上是独立的「阵容」图标，平板（OPPO Pad 4 Pro）右上只有
     * 「菜单」与「设置」两个圆图标、形状完全不同。用手机模板匹配平板，RMS 远高于门限，
     * `formationIcon` 恒为 null，导入流程就一直卡在「等待阵容列表」，永远点不到菜单。
     */
    private final Map<String,int[]> anchors=new HashMap<>();
    private final List<PortraitMatcher.Reference> portraits=new ArrayList<>();
    static String normalize(String s) { return s.replaceAll("[^\\p{IsHan}A-Za-z0-9]", ""); }
    static final class Entry { String text;Rect box; Entry(String t,Rect b){text=normalize(t);box=new Rect(b);} }
    static final class Screen {
        final List<Entry> entries=new ArrayList<>();
        boolean layoutValid=true,shopOpen,board,menu,replacement,targetMatches,targetApplied,previewBlocked;
        /** 尺寸与版式不匹配但还没到判定失败的帧数（转屏过渡），导入器据此重试而不是报错。 */
        boolean notReady;
        int favoriteRows,targetRow=2;
        Rect targetTitle,previewBounds;
        Point formationIcon,closeButton,importButton,replaceButton,applyButton,goldButton;
        PortraitMatcher.Row heroes;Bitmap preview;int[] rowSignature;
        /**
         * 需要为第 10 张卡滑动时，滑动的起点。
         *
         * <p>取目标行卡片带的中部靠左位置：纵向落在卡片中心，横向避开最右侧的滚动条与
         * 「应用/取消」按钮。拖动只改横向偏移，因此必须与目标行同高，否则会滑到别的行。
         */
        Point heroSwipeFrom;
        /** 收藏上限弹窗的替换区里实际 OCR 到的文字，用于排查「按钮认不到」。 */
        String replaceAreaText="";
        /** 阵容列表的两个判据是否各自认出，用于排查「读到很多字却判成未识别页面」。 */
        boolean menuHeaderOk,menuImportOk;
        Rect find(String word,int[] area) {
            String key=normalize(word); Rect region=new Rect(area[0],area[1],area[2],area[3]);Rect best=null;
            for(Entry e:entries)if(e.text.contains(key)&&region.contains(e.box.centerX(),e.box.centerY())&&
                (best==null || e.box.width()*e.box.height()<best.width()*best.height()))best=e.box;
            return best;
        }
        boolean has(String word,int width,int height){return find(word,new int[]{0,0,width,height})!=null;}
    }
    /** 头像描迹与版式解耦：这里只加载目录头像，几何参数由每次 read 传入的版式决定。 */
    LineupVision(Context context, ChampionCatalog catalog) {
        this.context=context;
        try {
            for(ChampionCatalog.Champion row:catalog.champions()) {
                String id=row.id;
                try(InputStream icon=catalog.portraitStream(id)) {
                    Bitmap b=BitmapFactory.decodeStream(icon);if(b==null)continue;
                    int[] pixels=new int[b.getWidth()*b.getHeight()];b.getPixels(pixels,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());
                    portraits.add(new PortraitMatcher.Reference(id,row.name,PortraitMatcher.descriptor(pixels,b.getWidth(),0,0,b.getWidth(),b.getHeight())));b.recycle();
                }
            }
        }catch(IOException e){throw new IllegalStateException("阵容头像目录读取失败",e);}
    }
    Screen read(Bitmap image,LineupLayout.Spec spec,String expected,boolean wantPortraits) { return read(image,spec,expected,wantPortraits,false); }
    Screen read(Bitmap image,LineupLayout.Spec spec,String expected,boolean wantPortraits,boolean forceThird) {
        Screen out=new Screen();Bitmap copy=image.copy(Bitmap.Config.ARGB_8888,false);Task<Text> task=null;
        try {
            task=ocr.process(InputImage.fromBitmap(copy,0)); Text text=Tasks.await(task,3,TimeUnit.SECONDS);
            for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()) {
                if(line.getBoundingBox()!=null)out.entries.add(new Entry(line.getText(),line.getBoundingBox()));
                for(Text.Element element:line.getElements())if(element.getBoundingBox()!=null)out.entries.add(new Entry(element.getText(),element.getBoundingBox()));
                // 竖排/拆字时整行被切碎，按块拼接后重新定位关键词框
                StringBuilder joined=new StringBuilder();List<Text.Element> elements=line.getElements();
                for(Text.Element e:elements)joined.append(normalize(e.getText()));
                for(String word:new String[]{"导入代码","已达到收藏上限","请选择已收藏的阵容进行替换","购买经验","请选择一个强化符文"}) {
                    int start=joined.indexOf(word);if(start<0)continue;int pos=0;Rect bound=null;
                    for(Text.Element e:elements){int end=pos+normalize(e.getText()).length();if(end>start&&pos<start+word.length()&&e.getBoundingBox()!=null){if(bound==null)bound=new Rect(e.getBoundingBox());else bound.union(e.getBoundingBox());}pos=end;}
                    if(bound!=null)out.entries.add(new Entry(word,bound));
                }
            }
        }catch(Exception e){throw new IllegalStateException("阵容界面识别暂未成功",e);}
        finally { if(task==null||task.isComplete())copy.recycle();else task.addOnCompleteListener(Runnable::run,t->copy.recycle()); }
        Rect header=out.find("阵容",spec.headerArea), prompt=out.find("提示",spec.promptArea);
        Rect importBox=out.find("导入代码",spec.importArea);
        // OCR 常把「码」认成「马」或漏字，逐级放宽关键词。
        if(importBox==null)importBox=out.find("代码",spec.importArea);
        if(importBox==null)importBox=out.find("导入",spec.importArea);
        if(importBox==null)importBox=out.find("导人",spec.importArea);
        if(importBox==null)importBox=out.find("導",spec.importArea);
        out.importButton=center(importBox);
        out.menuHeaderOk=header!=null;out.menuImportOk=out.importButton!=null;
        out.replacement=out.has("已达到收藏上限",image.getWidth(),image.getHeight())||out.has("请选择已收藏",image.getWidth(),image.getHeight());
        // 「阵容列表」要求抬头与导入按钮同时认出。实测同一画面同一坐标点击后，有时只读到 71 条文字
        // 却认不出其中一项（OCR 把「阵容」或「导入代码」整块漏掉），于是被误判成「未识别页面」。
        // 只要抬头认出、且不是收藏上限弹窗，就按阵容列表处理——导入按钮认不到会由导入器单独重试。
        out.menu=(header!=null||out.importButton!=null)&&!out.replacement;
        Rect closeHeader=out.replacement?prompt:header;
        if(closeHeader!=null)out.closeButton=new Point(closeHeader.left+spec.closeDx,spec.closeY);
        // 只放宽到「替换」两个字的常见误读，不用单字：单字容易在密集文字里误命中。
        // （替换区里除了这个按钮没有别的文字，但 OCR 会把邻近行标题的碎片也塞进来。）
        out.replaceButton=center(findButton(out,spec.replaceArea,"替换","替換","替换","替挽"));
        // OCR 偶尔读不出按钮文字。把替换区里实际读到的字记下来，否则只能靠猜是「字被认错」
        // 还是「按钮本来就不存在」（实测第三套已是同一套阵容时，游戏会换成别的提示）。
        StringBuilder replaceText=new StringBuilder();
        for(Entry entry:out.entries) {
            int cx=entry.box.centerX(),cy=entry.box.centerY();
            if(cx>=spec.replaceArea[0]&&cx<=spec.replaceArea[2]&&cy>=spec.replaceArea[1]&&cy<=spec.replaceArea[3]) {
                if(replaceText.length()>0)replaceText.append('/');
                replaceText.append(entry.text);
            }
        }
        out.replaceAreaText=replaceText.toString();
        Rect[] titles=new Rect[3],buttons=new Rect[3];boolean[] applied=new boolean[3];
        for(int row=0;row<3;row++) {
            int[] titles_=spec.titleBand(row), buttons_=spec.buttonBand(row);
            for(Entry entry:out.entries) if(entry.box.centerX()>=spec.titleLeft&&entry.box.centerX()<spec.titleRight&&entry.box.centerY()>=titles_[0]&&entry.box.centerY()<titles_[1]&&LineupCode.titleMatches(expected,entry.text))titles[row]=entry.box;
            int[] buttonArea=new int[]{spec.buttonLeft,buttons_[0],spec.buttonRight,buttons_[1]};
            // 只放宽到「应用」两个字的常见误读，**不要放宽成单字**：按钮区在「已应用」状态下
            // 显示的是「取消」，用单字「应」会把别处的字误命中，导致 applied 判断错乱。
            buttons[row]=findButton(out,buttonArea,"应用","應用","应周","应田");
            Rect cancel=out.find("取消",buttonArea);
            applied[row]=cancel!=null;if(buttons[row]==null)buttons[row]=cancel;
            if(buttons[row]!=null)out.favoriteRows++;
        }
        // 满收藏固定打第三套，即使别的行同名也不换行
        if(!forceThird&&out.favoriteRows<3 && titles[2]==null)for(int row=0;row<3;row++)if(titles[row]!=null){out.targetRow=row;break;}
        out.targetTitle=titles[out.targetRow];out.targetMatches=out.targetTitle!=null;out.targetApplied=applied[out.targetRow];
        if(!out.targetApplied)out.applyButton=center(buttons[out.targetRow]);
        // 抬头与「导入代码」都认不到时，若三行里认出了「应用/取消/替换」，说明阵容面板确实开着。
        // 实测面板与商店叠加时画面文字太多（130+ 条），OCR 会把「阵容」「导入代码」整块漏掉，
        // 只剩按钮还能认出来。不补这一条，`menu` 判假 → 状态机以为在棋盘 → 反复点菜单/关面板，
        // 用户看到的就是「手动打开阵容收藏也识别不出来」。
        if(!out.menu&&!out.replacement) {
            boolean anyButton=false;
            for(Rect b:buttons)if(b!=null){anyButton=true;break;}
            if(anyButton)out.menu=true;
        }
        int[] screenPixels=new int[image.getWidth()*image.getHeight()];image.getPixels(screenPixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());
        out.formationIcon=findIcon(screenPixels,image.getWidth(),spec);
        if(out.menu)out.rowSignature=PortraitMatcher.descriptor(screenPixels,image.getWidth(),spec.rowSignature(out.targetRow)[0],spec.rowSignature(out.targetRow)[1],spec.rowSignature(out.targetRow)[2],spec.rowSignature(out.targetRow)[3]);
        // **阵容面板开着时必须判为「不在棋盘」**：面板是浮在棋盘上方的半透明浮层，底下的
        // 「购买经验」按钮与右上角菜单图标依然可见、依然能被 OCR/模板读到，所以
        // `has("购买经验") || formationIcon != null` 在面板开着时同样成立。
        // 不排除这一段，`board` 会与 `menu` 同时为真，状态机 PREPARE 先判 board 就会
        // 反复去点「打开菜单」或「关闭面板」——实测日志里满是
        // `menu=false ... board=true` 后紧跟「确认应用并关闭面板」，用户看到的就是
        // 「一直卡在游戏主界面」「手动打开阵容收藏也识别不出来」。
        boolean lineupVisible=out.menu||out.replacement;
        out.board=!lineupVisible&&(out.has("购买经验",image.getWidth(),image.getHeight())||out.formationIcon!=null)&&!out.has("请选择一个强化符文",image.getWidth(),image.getHeight())&&!out.has("对局结束",image.getWidth(),image.getHeight())&&!out.has("选择强化符文",image.getWidth(),image.getHeight())&&!out.has("再来一局",image.getWidth(),image.getHeight())&&!out.has("返回大厅",image.getWidth(),image.getHeight())&&!out.has("退出对局",image.getWidth(),image.getHeight());
        if(out.menu&&out.targetMatches&&wantPortraits) {
            out.heroes=PortraitMatcher.readRow(screenPixels,image.getWidth(),spec,out.targetRow,portraits);
            if(out.heroes.moreRight) {
                // 拖动起点必须落在**本行卡片**内：纵向直接用实测的首卡 y（标定表的行 y 与
                // 真机渲染有几十像素偏差），横向取首卡与视口左沿中较右者，避开滚动条与按钮。
                int side=spec.avatar(out.targetRow)[2];
                int left=Math.max(spec.avatar(out.targetRow)[0],out.heroes.x);
                out.heroSwipeFrom=new Point(left+side/2,out.heroes.y+side/2);
            }
        }
        // 阵容预览截图已取消（2026-11-17 用户要求）：保存预览要在收尾阶段再做一次裁剪，
        // 而在「已在游戏中导入」这条主路径上它只是额外负担——每帧多一次整屏裁剪拷贝，
        // 收尾时还会因为拿不到图直接判「没有取得阵容预览，请重新导入」，把已经成功的导入变成失败。
        // 头像识别与它无关，仍按上面的 wantPortraits 走。previewBounds 保留，供调试查看识别落点。
        return out;
    }
    /**
     * 在指定区域里按关键词逐级放宽地找按钮文字。
     *
     * <p>**为什么需要容错**：按钮只有两个字（替换/应用），OCR 少认一个字整帧就失败。
     * 实测「替换界面明明开着却报没识别到替换按钮」就是这类漏字——单帧漏字很随机，
     * 与阵容内容无关。这里只放宽到「单字」为止：这些区域里除了按钮本身没有别的文字，
     * 而且 `replaceArea` 已收窄到只含第三行按钮，放宽不会误命中别的东西。
     */
    private static Rect findButton(Screen screen,int[] area,String... keywords) {
        for(String keyword:keywords) {
            Rect box=screen.find(keyword,area);
            if(box!=null)return box;
        }
        return null;
    }
    private int[] anchorFor(LineupLayout.Spec spec) {
        int[] cached=anchors.get(spec.iconAsset);
        if(cached!=null)return cached;
        try(InputStream in=context.getAssets().open(spec.iconAsset)) {
            byte[] bytes=in.readAllBytes();int[] values=new int[bytes.length];
            for(int i=0;i<bytes.length;i++)values[i]=bytes[i]&255;
            anchors.put(spec.iconAsset,values);return values;
        }catch(IOException e){throw new IllegalStateException("缺少阵容入口模板 "+spec.iconAsset,e);}
    }
    /** 在棋盘右上角搜索阵容图标模板；相似度 RMS 小于版式门限视为在棋盘上。 */
    private Point findIcon(int[] pixels,int width,LineupLayout.Spec spec) {
        int[] menuAnchor=anchorFor(spec);
        int[] area=spec.iconSearch;int size=area[4];
        double best=Double.MAX_VALUE;Point result=null;
        for(int y=area[1];y<=area[3];y+=2)for(int x=area[0];x<=area[2];x+=2) {
            int[] candidate=PortraitMatcher.descriptor(pixels,width,x,y,size,size);double sum=0;
            for(int i=0;i<candidate.length;i++){double d=candidate[i]-menuAnchor[i];sum+=d*d;}
            double score=Math.sqrt(sum/candidate.length);if(score<best){best=score;result=new Point(x+size/2,y+size/2);}
        }
        return best<=spec.iconThreshold?result:null;
    }
    private static Point center(Rect rect) { return rect==null?null:new Point(rect.centerX(),rect.centerY()); }
    @Override public void close(){ocr.close();}
}
