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
     * <p>先在 {@code avatarProbe} 区域粗搜第一张卡的落点，再逐格按卡位间距横向推进；
     * 每格允许 ±4 像素微调。宽度或方差不足说明该格是空位，立即结束本行。
     */
    static Row readRow(int[] pixels,int width,LineupLayout.Spec spec,int rowIndex,List<Reference> refs) {
        Row row=new Row(); Match first=null;
        int[] probe=spec.avatarProbe(rowIndex), box=spec.avatar(rowIndex);
        int side=box[2], height=box[3];
        for(int y=probe[1];y<=probe[3];y+=6) for(int x=probe[0];x<=probe[2];x+=6) {
            Match m=match(descriptor(pixels,width,x,y,side,height),refs,x,y);
            if(first==null || m.distance<first.distance)first=m;
        }
        if(first==null)return row;
        int baseX=first.x,baseY=first.y;
        for(int y=baseY-5;y<=baseY+5;y+=2) for(int x=baseX-5;x<=baseX+5;x+=2) {
            Match m=match(descriptor(pixels,width,x,y,side,height),refs,x,y);
            if(m.distance<first.distance)first=m;
        }
        if(!first.accepted())return row;
        row.x=first.x;row.y=first.y;
        for(int slot=0;slot<spec.avatarSlots;slot++) {
            int x=row.x+slot*spec.avatarPitch;
            if(x+side>spec.avatarRight)break;
            Match best=null;
            for(int dy=-4;dy<=4;dy+=2)for(int dx=-4;dx<=4;dx+=2) {
                Match m=match(descriptor(pixels,width,x+dx,row.y+dy,side,height),refs,x+dx,row.y+dy);
                if(best==null || m.distance<best.distance)best=m;
            }
            if(best.variance<18)break;
            row.count++;
            if(best.accepted()) { if(!row.names.contains(best.name))row.names.add(best.name); }
            else row.unknown++;
        }
        return row;
    }
}
