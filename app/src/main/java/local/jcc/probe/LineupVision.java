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
    private int[] menuAnchor;
    private final List<PortraitMatcher.Reference> portraits=new ArrayList<>();
    static String normalize(String s) { return s.replaceAll("[^\\p{IsHan}A-Za-z0-9]", ""); }
    static final class Entry { String text;Rect box; Entry(String t,Rect b){text=normalize(t);box=new Rect(b);} }
    static final class Screen {
        final List<Entry> entries=new ArrayList<>();
        boolean layoutValid=true,shopOpen,board,menu,replacement,targetMatches,targetApplied,previewBlocked;
        int favoriteRows,targetRow=2;
        Rect targetTitle,previewBounds;
        Point formationIcon,closeButton,importButton,replaceButton,applyButton,goldButton;
        PortraitMatcher.Row heroes;Bitmap preview;int[] rowSignature;
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
        try(InputStream in=context.getAssets().open(LineupLayout.ICON_ASSET)) {
            byte[] bytes=in.readAllBytes();menuAnchor=new int[bytes.length];for(int i=0;i<bytes.length;i++)menuAnchor[i]=bytes[i]&255;
        }catch(IOException e){throw new IllegalStateException("缺少阵容入口模板",e);}
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
        out.importButton=center(importBox);
        out.replacement=out.has("已达到收藏上限",image.getWidth(),image.getHeight())||out.has("请选择已收藏",image.getWidth(),image.getHeight());
        out.menu=header!=null&&out.importButton!=null&&!out.replacement;
        Rect closeHeader=out.replacement?prompt:header;
        if(closeHeader!=null)out.closeButton=new Point(closeHeader.left+spec.closeDx,spec.closeY);
        out.replaceButton=center(out.find("替换",spec.replaceArea));
        Rect[] titles=new Rect[3],buttons=new Rect[3];boolean[] applied=new boolean[3];
        for(int row=0;row<3;row++) {
            int[] titles_=spec.titleBand(row), buttons_=spec.buttonBand(row);
            for(Entry entry:out.entries) if(entry.box.centerX()>=spec.titleLeft&&entry.box.centerX()<spec.titleRight&&entry.box.centerY()>=titles_[0]&&entry.box.centerY()<titles_[1]&&LineupCode.titleMatches(expected,entry.text))titles[row]=entry.box;
            buttons[row]=out.find("应用",new int[]{spec.buttonLeft,buttons_[0],spec.buttonRight,buttons_[1]});
            Rect cancel=out.find("取消",new int[]{spec.buttonLeft,buttons_[0],spec.buttonRight,buttons_[1]});
            applied[row]=cancel!=null;if(buttons[row]==null)buttons[row]=cancel;
            if(buttons[row]!=null)out.favoriteRows++;
        }
        // 满收藏固定打第三套，即使别的行同名也不换行
        if(!forceThird&&out.favoriteRows<3 && titles[2]==null)for(int row=0;row<3;row++)if(titles[row]!=null){out.targetRow=row;break;}
        out.targetTitle=titles[out.targetRow];out.targetMatches=out.targetTitle!=null;out.targetApplied=applied[out.targetRow];
        if(!out.targetApplied)out.applyButton=center(buttons[out.targetRow]);
        int[] screenPixels=new int[image.getWidth()*image.getHeight()];image.getPixels(screenPixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());
        out.formationIcon=findIcon(screenPixels,image.getWidth(),spec);
        if(out.menu)out.rowSignature=PortraitMatcher.descriptor(screenPixels,image.getWidth(),spec.rowSignature(out.targetRow)[0],spec.rowSignature(out.targetRow)[1],spec.rowSignature(out.targetRow)[2],spec.rowSignature(out.targetRow)[3]);
        out.board=(out.has("购买经验",image.getWidth(),image.getHeight())||out.formationIcon!=null)&&!out.has("请选择一个强化符文",image.getWidth(),image.getHeight())&&!out.has("对局结束",image.getWidth(),image.getHeight())&&!out.has("选择强化符文",image.getWidth(),image.getHeight())&&!out.has("再来一局",image.getWidth(),image.getHeight())&&!out.has("返回大厅",image.getWidth(),image.getHeight())&&!out.has("退出对局",image.getWidth(),image.getHeight());
        if(out.menu&&out.targetMatches&&wantPortraits) {
            out.heroes=PortraitMatcher.readRow(screenPixels,image.getWidth(),spec,out.targetRow,portraits);
            int top=out.targetTitle.top-8;
            int bottom=out.heroes.count>0?out.heroes.y+spec.previewRowHeight:out.targetTitle.bottom+210;
            int left=out.heroes.count>0?Math.min(out.targetTitle.left,out.heroes.x)-8:out.targetTitle.left-8;
            Point targetButton=center(buttons[out.targetRow]);
            int buttonLeft=targetButton==null?spec.previewRightMax:targetButton.x-spec.buttonHalfWidth;
            int right=out.heroes.count>0?out.heroes.x+(out.heroes.count-1)*spec.avatarPitch+spec.avatar[2]+10:buttonLeft-2;
            right=Math.min(right,buttonLeft-2);bottom=Math.min(bottom,spec.previewBottomMax);
            out.previewBounds=new Rect(Math.max(0,left),Math.max(0,top),right,bottom);
            for(String message:new String[]{"收藏成功","导入成功","阵容收藏成功"}) {
                Rect toast=out.find(message,new int[]{0,0,image.getWidth(),image.getHeight()});
                if(toast!=null&&Rect.intersects(toast,out.previewBounds))out.previewBlocked=true;
            }
            out.preview=Bitmap.createBitmap(image,out.previewBounds.left,out.previewBounds.top,out.previewBounds.width(),out.previewBounds.height());
        }
        return out;
    }
    /** 在棋盘右上角搜索阵容图标模板；相似度 RMS 小于版式门限视为在棋盘上。 */
    private Point findIcon(int[] pixels,int width,LineupLayout.Spec spec) {
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
