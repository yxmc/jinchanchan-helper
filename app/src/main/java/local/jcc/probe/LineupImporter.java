package local.jcc.probe;

import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One user-selected import. Every gesture requires a newly recognized game screen. */
final class LineupImporter {
    private final CaptureService service;
    private final DeckStore.Deck deck;
    private final LineupCode code;
    private final LineupFlow flow=new LineupFlow();
    private LineupVision vision;
    private volatile boolean active=true;
    private boolean inFlight,finishing;
    private volatile boolean requireThirdRow;
    private final boolean needsPreview;
    private long phaseAt=SystemClock.elapsedRealtime();
    private int failures;
    /** 尺寸对不上版式的连续帧数：转屏过渡帧先重试几次，避免误报「机型不支持」。 */
    private int unsupportedFrames;
    private static final int UNSUPPORTED_RETRIES=3;
    /** 复制阵容码后等剪贴板落定再点「导入代码」，避免游戏读到旧内容或空内容。 */
    private static final long CLIPBOARD_SETTLE_MS=280;
    /** 按钮暂时认不到时的连续重试帧数；弹窗渐入/OCR 漏字都会造成单帧缺失。 */
    private int missingButtonFrames;
    private static final int MISSING_BUTTON_RETRIES=6;
    /**
     * 单个阶段的最长等待。取 60 秒是为了覆盖「从后台切回游戏、游戏重新加载」的情况——
     * 用户实测后台返回后游戏要重新加载 5 秒以上，加上 OCR 每帧 0.3~1 秒，25 秒曾经过早判死。
     */
    private static final long STAGE_TIMEOUT_MS=60000;
    private Bitmap preview;
    private int[] expectedRowSignature;
    private List<String> heroes=new ArrayList<>();
    private int heroesUnknown;
    private boolean heroesComplete;
    /**
     * 滑动只尝试一次，并用「首卡位置有没有变」来判断是否真有第 10 张卡。
     *
     * <p>**这是唯一可靠的判据**：阵容上限 10 个英雄，但绝大多数阵容就是 9 个，
     * 此时卡片行内容正好填满、根本不可滚动。对不可滚动的行发滑动，手势会被游戏
     * 直接忽略——实测日志里 `lineup_swipe` 发出去了、1.4 秒后首卡仍是原位。
     * 而真有 10 张时滑一次就会滚动 112px、首卡从 x=533 变成 585。
     *
     * <p>所以「滑动后位置不变」本身就是「没有第 10 张」的结论，不该重试（重试也滑不动），
     * 直接判定这一行读全即可。反过来只看「读到 9 格」就去滑，会让每个 9 英雄的阵容
     * 白等一次滑动的时间。
     */
    private int heroSwipeTries;
    private static final int HERO_SWIPE_MAX=1;
    /**
     * 滑动前该行的首卡 x，用于校验滑动是否真的让卡片动起来。
     *
     * <p>滑完首卡位置纹丝未动 = 这一行不可滚动 = 本来就没有第 10 张。
     */
    private int heroSwipeAnchorX=-1;
    /**
     * 本帧所用版式的滑动量。**不要写死**：滑动量必须覆盖一个卡位间距才够把第 10 张滑出来，
     * 而三个机型的卡位间距差很多（154 / 164 / 241）。平板曾沿用手机的 -105，实测首卡只从
     * 493 移到 415（78px），第 10 张只在 x2588..2609 露一条 21px 的边，175 取样框放不进去，
     * 于是「第三排有 10 个棋子」时第 10 个永远读不到。取值见 {@link LineupLayout.Spec#heroSwipeDx}。
     */
    private int heroSwipeDx=-105;
    /**
     * 本机型能否「点总金币收起商店」。手机 true、平板 false。
     *
     * <p>平板上该交互不存在（实测连点 12 次、手动点也无反应），若状态机等商店收起会一直
     * 空转到超时。取值见 {@link LineupLayout.Spec#canCloseShop}。
     */
    private boolean canCloseShop=true;
    /** begin() 里那次剪贴板回读是否一致；IMPORT 分支据此决定要不要提示用户。 */
    private boolean clipboardOk;
    private final Runnable tick=this::tick;
    LineupImporter(CaptureService service,DeckStore.Deck deck) {this.service=service;this.deck=deck;this.code=LineupCode.parse(deck.code);needsPreview=true;}
    boolean active(){return active;}
    void begin(){copyCode();schedule(350);}
    /**
     * 把阵容码写进剪贴板，并回读校验。
     *
     * <p>**这里有一个 Android 12+ 的系统限制必须绕开**：应用退到后台后，对剪贴板的读写都会被
     * 系统就地拦掉——`setPrimaryClip` 被静默丢弃，`getPrimaryClip` 直接返回 null。
     * 实测同一段流程 16 次回读一致、10 次不一致，**不一致的 10 次全部发生在「已经切到游戏、
     * 点导入代码前又复制一次」的时刻**，日志为 `剪贴板回读不一致：期望 59 字，实际 4 字[null]`；
     * 而 {@link #begin()} 在助手仍在前台时复制的那次都是「回读一致」。
     *
     * <p>所以剪贴板只在 {@code begin()} 里写一次（此时助手在前台），
     * {@code IMPORT} 分支**不再重写**——重写既无效，还会把先前写好的内容冲掉。
     */
    private void copyCode(){
        String detail="";
        try {
            android.content.ClipboardManager manager=(android.content.ClipboardManager)service.getSystemService(Context.CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText(deck.name,code.text));
            CharSequence got=null;
            ClipData clip=manager.getPrimaryClip();
            if(clip!=null&&clip.getItemCount()>0)got=clip.getItemAt(0).coerceToText(service);
            String readBack=got==null?"null":got.toString();
            clipboardOk=readBack.equals(code.text);
            detail=clipboardOk?"剪贴板回读一致（"+readBack.length()+" 字）"
                :"剪贴板回读不一致：期望 "+code.text.length()+" 字，实际 "+readBack.length()+" 字["+readBack+"]";
        } catch(RuntimeException e) {
            clipboardOk=false;
            detail="剪贴板写入异常："+e;
        }
        service.importStatus("阵容码已复制："+deck.name+" · "+detail+" · 收起面板并切回游戏后导入");
    }
    private void schedule(long ms){if(active)service.scheduleLineup(tick,ms);}
    private void tick(){
        if(!active||inFlight||finishing)return;
        if(!service.importAllowed()) {phaseAt=SystemClock.elapsedRealtime();service.importStatus("阵容导入待命：请收起面板并保持游戏前台");schedule(400);return;}
        // 超时只针对「一直等不到该出现的界面」。不能用单一 25 秒卡死整条流程：
        // 用户可能从后台返回（游戏要重新加载 5 秒以上），也可能是悬浮窗切全屏（游戏不重载）。
        // 前者在 WAIT_MENU 上停留很久是正常的，给足时间；后者几乎立刻就绪。
        if(SystemClock.elapsedRealtime()-phaseAt>STAGE_TIMEOUT_MS){
            fail("阵容导入等待超时（"+flow.stage()+"），未继续点击；请检查游戏界面后重试");return;
        }
        inFlight=true;
        boolean started=service.captureForLineup(image->{
            // 阵容页版式按分辨率查表：小米 14 与 K80 各自一套实测坐标，未标定机型直接判不可用。
            LineupLayout.Spec spec=LineupLayout.find(image.getWidth(),image.getHeight());
            // 失败原因必须落进 trace：以前只在 Toast 里提示，事后查日志看不到是尺寸不对还是真的没标定。
            // 尺寸对不上时先按「还在转屏」重试几次，不能立刻当成机型不支持。
            if(spec==null){
                if(++unsupportedFrames<UNSUPPORTED_RETRIES){
                    service.importStatus("阵容导入：等待横屏画面（当前 "+image.getWidth()+"×"+image.getHeight()+"）");
                    LineupVision.Screen s=new LineupVision.Screen();s.notReady=true;return s;
                }
                service.importStatus("阵容导入不支持 "+image.getWidth()+"×"+image.getHeight()+"："+LineupLayout.unsupportedMessage());
                LineupVision.Screen s=new LineupVision.Screen();s.layoutValid=false;return s;
            }
            unsupportedFrames=0;
            // 滑动量按机型取：三个机型卡位间距差很多（154/164/241），写死一份必然在某个机型上
            // 滑不够一格，第 10 张就永远露不出来。
            heroSwipeDx=spec.heroSwipeDx;
            // 机型能力：平板没有「点金币收商店」这个交互，不能在收商店上等待。
            canCloseShop=spec.canCloseShop;
            if(vision==null)vision=new LineupVision(service,ChampionCatalog.of(service,service.championSet()));
            // 头像识别仍然需要（拿卡靠它），只是不再顺带裁预览图。原来这个开关叫 needsPreview，
            // 现在它实际控制的是「要不要读这一行的英雄头像」，保持为真即可。
            boolean wantReadHeroes=!heroesComplete&&flow.stage()!=LineupFlow.Stage.PREPARE&&flow.stage()!=LineupFlow.Stage.WAIT_MENU;
            LineupVision.Screen screen=vision.read(image,spec,code.name,wantReadHeroes,requireThirdRow);
            // 记下本帧的尺寸与 OCR 命中情况：全为 false 时要么画面不是阵容页，要么 OCR 一个词都没读出来，
            // 这两者的排查方向完全不同，日志里必须能区分。
            service.importStatus("阵容导入： "+image.getWidth()+"×"+image.getHeight()+" 识别到 "+screen.entries.size()+" 条文字"
                +(screen.menu?" · 阵容列表":screen.replacement?" · 收藏上限弹窗":screen.board?" · 棋盘":" · 未识别页面")
                +(screen.menu?"":" 抬头="+screen.menuHeaderOk+" 导入按钮="+screen.menuImportOk)
                +(screen.replacement&&screen.replaceButton==null?" · 替换区文字["+screen.replaceAreaText+"]":"")
                +(screen.heroes!=null?" · 头像["+screen.heroes.names+" 未知"+screen.heroes.unknown+"/"+screen.heroes.count
                    +" 首卡("+screen.heroes.x+","+screen.heroes.y+") "+screen.heroes.diagnostic+"]":""));
            if(!screen.menu&&!screen.replacement) {
                BuyPolicy.Frame shop=service.readShopForLineup(image);screen.shopOpen=shop.shopOpen;
                PhoneLayout.Spec shopSpec=PhoneLayout.find(image.getWidth(),image.getHeight());
                // 点击金币框中心收起商店：小米 14 圆心落在 (2526,1067)，与改造前硬编码的 1067 一致。
                if(shopSpec!=null)screen.goldButton=new Point(shopSpec.gold[0]+shopSpec.gold[2]/2+shop.offsetX,
                    shopSpec.gold[1]+shopSpec.gold[3]/2);
            }
            return screen;
        },(screen,error)->{
            inFlight=false;
            if(!active){recycle(screen);return;}
            if(error!=null){if(++failures>=4){fail("阵容画面暂未读清："+error.getMessage());return;}schedule(500);return;}
            failures=0;
            if(screen.notReady){recycle(screen);phaseAt=SystemClock.elapsedRealtime();schedule(400);return;}
            if(!screen.layoutValid){recycle(screen);fail(LineupLayout.unsupportedMessage());return;}
            if(!service.importAllowed()){recycle(screen);phaseAt=SystemClock.elapsedRealtime();schedule(400);return;}
            // 预览截图已取消，不再因「收藏成功」提示遮挡而等待。
            if(screen.favoriteRows>=3||screen.replacement)requireThirdRow=true;
            LineupFlow.View state=new LineupFlow.View();state.board=screen.board;state.shop=screen.shopOpen;state.menu=screen.menu;
            // 机型能力：平板没有「点金币收商店」的交互，状态机据此不再等商店收起。
            state.canCloseShop=canCloseShop;
            state.full=requireThirdRow||screen.favoriteRows>=3;state.replacement=screen.replacement;state.targetMatches=screen.targetMatches;state.applied=screen.targetApplied;
            // Only after this exact row was verified and Apply was dispatched may a
            // transient title OCR failure be confirmed by the unchanged portrait.
            if(flow.stage()==LineupFlow.Stage.WAIT_CLOSED_FINAL && screen.menu && screen.targetApplied && sameRow(expectedRowSignature,screen.rowSignature))state.targetMatches=true;
            LineupFlow.Stage before=flow.stage();LineupFlow.Action action=flow.next(state);
            if(action==LineupFlow.Action.APPLY_TARGET)expectedRowSignature=screen.rowSignature;
            if(flow.stage()!=before||flow.stage()==LineupFlow.Stage.PREPARE&&action==LineupFlow.Action.WAIT)phaseAt=SystemClock.elapsedRealtime();
            // 头像名单直接取本帧识别结果，不再依赖预览图：预览截图已取消，但它只是显示用的
            // 附加数据，拿卡真正需要的 heroes 名单必须照旧收下来。
            boolean heroFrame=screening(action,screen);
            if(screen.heroes!=null && heroFrame)mergeHeroes(screen.heroes);
            // 已经滑过一次、这一帧又读到目标行时，用首卡位置的变化判定「到底有没有第 10 张」。
            // 位置变了说明行被滚动了、原来末尾还压着一张；位置没变说明这行不可滚动、
            // 本来就只有读到的这么多。
            if(heroSwipeTries>0 && heroSwipeAnchorX>=0 && screen.heroes!=null && heroFrame) {
                boolean scrolled=screen.heroes.x!=heroSwipeAnchorX;
                service.importStatus("阵容导入：滑动校验 首次卡x="+heroSwipeAnchorX+" 本次="+screen.heroes.x
                    +(scrolled?" · 行已滚动，已合并两次读取":" · 行未滚动，本行没有第 10 张"));
                heroSwipeAnchorX=-1;
            }
            // 这一行读满了 9 格，说明阵容可能是 10 个英雄、第 10 张还停在面板右缘外。
            // 先滑一次再读，两次名单合并（第一次读到 1~9、第二次读到 2~10，并集就是 10 个）。
            //
            // **不能只在 APPLY_TARGET 时滑**：如果第三套收藏本来就是这套阵容（按钮显示
            // 「取消」，applied=true），状态机会直接走 CLOSE_EXISTING 而永远不给 APPLY_TARGET，
            // 滑动就整个被跳过——实测同一套阵容第二次导入时日志里没有滑动，名单又只剩 9 个。
            // 滑动只跟「这一行有没有读满、还有没有滑过」有关，与接下来要点哪个按钮无关。
            if(heroFrame && screen.heroes!=null && screen.heroes.moreRight
                && heroSwipeTries<HERO_SWIPE_MAX && screen.heroSwipeFrom!=null) {
                heroSwipeTries++;
                heroSwipeAnchorX=screen.heroes.x;
                // 回退一格，下一帧仍会给出同一个动作，但那时读到的已经是滑动后的行。
                flow.rewind(action);
                final Point from=screen.heroSwipeFrom;
                recycle(screen);
                service.importStatus("阵容导入：读满 "+screen.heroes.count+" 格，滑动一次确认是否还有第 10 张");
                // 等久一点再读：实测成功的滑动是 1.8 秒后才读到新位置，1.4 秒时画面还是旧的。
                if(!service.swipeForLineup(from,heroSwipeDx,this,()->{inFlight=false;schedule(900);}))
                    {inFlight=false;fail("当前界面或动作状态已变化，请重试导入");}
                return;
            }
            // 诊断必须在 recycle 之前取：recycle 只回收 preview，但把「本帧判据」提前算好
            // 更不容易在将来被改动破坏。
            final String detail=waitDetail(screen);
            recycle(screen);
            switch(action){
                case FINISH:finish();return;
                case WAIT:service.importStatus("阵容导入："+phaseLabel()+detail);schedule(400);return;
                default:break;
            }
            Point target=null;String label="";
            switch(action){
                case CLOSE_EXISTING:target=screen.closeButton;label="关闭阵容面板";break;
                case CLOSE_SHOP:target=screen.goldButton;label="点击总金币收起商店";break;
                case OPEN_MENU:target=screen.formationIcon;label="打开游戏阵容";break;
                case IMPORT:
                    // **不要在这里重写剪贴板**：此刻助手已退到后台，Android 12+ 会拦掉读写，
                    // 重写只会把 begin() 写好的内容冲掉（实测回读变成 null，游戏报「请输入有效阵容码」）。
                    if(!clipboardOk) service.importStatus("阵容导入：开始前的剪贴板校验未通过，游戏可能提示阵容码无效");
                    target=screen.importButton;label="导入剪贴板阵容码";break;
                case REPLACE_THIRD:target=screen.replaceButton;label="替换第三套收藏阵容";break;
                case APPLY_TARGET:target=screen.applyButton;label="应用第三套阵容";break;
                default:break;
            }
            if(target==null){
                // 按钮暂时没读到不等于不存在：弹窗渐入、OCR 抖动、提示文字遮挡都会让某一帧漏字。
                // 先把状态机回退一格，下一帧才会重新给出同一个动作；否则状态已推进到等「菜单」，
                // 在替换弹窗里永远等不到，重试也就永远不生效。
                flow.rewind(action);
                if(++missingButtonFrames<MISSING_BUTTON_RETRIES){
                    service.importStatus("阵容导入：暂未读到“"+label+"”，重试中（"+missingButtonFrames+"/"+MISSING_BUTTON_RETRIES+"）");
                    schedule(400);return;
                }
                fail("没有明确识别到“"+label+"”按钮，已停止导入");return;
            }
            missingButtonFrames=0;
            service.importStatus("阵容导入："+label);inFlight=true;
            // 「导入代码」必须先让剪贴板落定再点：Android 13+ 从无障碍服务写剪贴板是异步提交的，
            // 复制完立刻派发点击，游戏弹 prompt 去 getPrimaryClip() 很可能还是旧内容或空，
            // 于是报「请输入有效阵容码」。实测同一条流程时好时坏正是这个竞争导致的。
            final Point tapTarget=target;
            final long clipboardDelay=action==LineupFlow.Action.IMPORT?CLIPBOARD_SETTLE_MS:0;
            if(clipboardDelay>0){
                service.scheduleLineup(()->{
                    if(!active){inFlight=false;return;}
                    if(!service.tapForLineup(tapTarget,this,()->{inFlight=false;schedule(450);})) {inFlight=false;fail("当前界面或动作状态已变化，请重试导入");}
                },clipboardDelay);
                return;
            }
            if(!service.tapForLineup(tapTarget,this,()->{inFlight=false;schedule(450);})) {inFlight=false;fail("当前界面或动作状态已变化，请重试导入");}
        });
        if(!started){inFlight=false;schedule(150);}
    }
    /**
     * 这一帧的动作是否伴随「读取目标行头像」。
     *
     * <p>两个时机都会读到目标行：准备点「应用」、或发现这一行已经是目标阵容而要关闭面板。
     * 滑动补读第 10 张必须在**任何一种**读到行的情况下都能触发，不能只挂在 APPLY_TARGET 上。
     */
    private static boolean screening(LineupFlow.Action action,LineupVision.Screen screen){
        if(action==LineupFlow.Action.APPLY_TARGET)return true;
        return action==LineupFlow.Action.CLOSE_EXISTING&&screen.menu&&screen.targetMatches;
    }
    /**
     * 合并一次读到的头像名单。
     *
     * <p>**取并集而不是覆盖**：真有 10 个英雄时，一行只排 9 格，第一次读到第 1~9 张、
     * 滑动后读到第 2~10 张（最左一张滑出画面），只有并集才是完整的 10 个。
     * 覆盖会让滑动那一次白读，更早读到的有效卡也会被冲掉。
     *
     * <p>`heroesUnknown` 取最小：未知数只用来判断「这一行有没有认不出的卡」，
     * 合并后任何一次读得干净，就说明这一行是能认全的。
     *
     * <p>**「读满 9 格」不等于「没读全」**：绝大多数阵容就是 9 个英雄，`moreRight`
     * 恒为真。判定读全的条件是「没有未知卡」且「已经给过滑动机会」——后者保证真有
     * 第 10 张时已经滑过去读了一次，否则会永远被判成不完整，每次都要用户手填。
     */
    private void mergeHeroes(PortraitMatcher.Row row){
        boolean first=heroes.isEmpty();
        for(String name:row.names)if(!heroes.contains(name))heroes.add(name);
        heroesUnknown=first?row.unknown:Math.min(heroesUnknown,row.unknown);
        boolean positionsExhausted=!row.moreRight||heroSwipeTries>=HERO_SWIPE_MAX;
        heroesComplete=positionsExhausted&&heroesUnknown==0;
    }
    /**
     * 等待阶段的补充说明：只在这两个阶段打印，否则日志会被大量重复行淹没。
     *
     * <p>`WAIT_CLOSED_FINAL` 曾经空转 27 帧（15 秒），而日志里只有一句重复的「确认应用并
     * 关闭面板」，看不出到底是标题没读到还是按钮没认出来。把三项判据都打出来，
     * 下次一眼就能定位。
     */
    private String waitDetail(LineupVision.Screen screen){
        if(flow.stage()==LineupFlow.Stage.WAIT_CLOSED_FINAL)
            return "（标题="+screen.targetMatches+" 已应用="+screen.targetApplied+"）";
        if(flow.stage()==LineupFlow.Stage.WAIT_APPLY)
            return "（标题="+screen.targetMatches+"）";
        return "";
    }
    private String phaseLabel(){switch(flow.stage()){
        case WAIT_CLOSED:return "等待商店收起";case WAIT_MENU:return "等待阵容列表";case WAIT_IMPORTED:return "等待导入或替换提示";
        case WAIT_APPLY:return "核对第三套阵容";case WAIT_CLOSED_FINAL:return "确认应用并关闭面板";default:return "等待游戏棋盘";}}
    private void finish(){
        finishing=true;service.importStatus("阵容已应用，正在保存英雄名单");
        service.runLineupWork(()->{
            String error=null;boolean verified=deck.verified;String note="";
            try {
                DeckStore store=new DeckStore(service);DeckStore.Deck latest=store.current();
                if(!latest.id.equals(deck.id)||!latest.code.equals(deck.code))throw new IOException("选中的阵容码已变化");
                // 不再保存阵容预览图（2026-11-17 用户要求取消阵容截图）。
                // 原来这里在拿不到图时抛「没有取得阵容预览，请重新导入」，会把一次已经成功
                // 应用到游戏里的导入报成失败；头像名单才是拿卡真正需要的数据，必须照旧保存。
                verified=heroesComplete;note=verified?"头像已识别":seasonHint();
                // 名单为空时**绝不覆盖**已保存的名单：某些界面下（比如没走到核对阶段）头像一行都没读到，
                // 直接写空会把用户原来能用的购买名单清掉，拿卡就整条链路失效了。
                if(deck.verified&&!deck.targets.isEmpty()){verified=true;store.saveDerived(deck.id,latest.targets,latest.preview,true,latest.note);}
                else if(!heroes.isEmpty()) store.saveDerived(deck.id,String.join("\n",heroes),latest.preview,verified,note);
                else {verified=deck.verified;note="头像未读到，保留原有购买名单";}
            }catch(Exception e){error=e.getMessage();}
            finally {if(preview!=null){preview.recycle();preview=null;}if(vision!=null){vision.close();vision=null;}}
            final String failure=error;final boolean ready=verified;final String message=note;
            service.scheduleLineup(()->{if(!active)return;active=false;service.lineupFinished(this,failure==null,
                failure!=null?"游戏已应用，保存名单失败："+failure:ready?"已应用阵容："+deck.name: "已应用阵容，请在助手补齐英雄后保存确认" );},0);
        });
    }
    static boolean sameRow(int[] expected,int[] actual){
        if(expected==null||actual==null||expected.length!=actual.length)return false;
        double sum=0;for(int i=0;i<expected.length;i++){double d=expected[i]-actual[i];sum+=d*d;}
        return Math.sqrt(sum/expected.length)<16;
    }
    /**
     * 头像没认全时的保存备注。
     *
     * <p>**认不全不一定是截图质量差，最常见的原因是赛季选错**：头像按所选赛季的随包目录比对，
     * 打 S16 返场时若还停在 S18（默认值），整行头像都会认成「未知」——实测同一张 K80 截图，
     * S16 目录 27/27 全中，S18 目录只有 1/27。这种「几乎全军覆没」与「零星一两个认错」要分开提示，
     * 否则用户会照着「请补齐英雄」一个个手填，而真正该做的是切赛季。
     */
    private String seasonHint(){
        int known=heroes.size(), total=known+heroesUnknown;
        if(total>0 && known*4<total) {
            int set=service.championSet();
            return "头像几乎全部未识别，很可能是赛季不匹配：当前为"
                +ChampionCatalog.label(set)+"，请核对游戏赛季后在首页切换并重新导入";
        }
        return "头像识别不完整，请补齐英雄后保存确认";
    }
    private static void recycle(LineupVision.Screen screen){if(screen!=null&&screen.preview!=null){screen.preview.recycle();screen.preview=null;}}
    void fail(String reason){if(!active)return;service.importStatus("阵容导入失败："+reason);cancel();service.lineupFinished(this,false,reason);}
    void cancel(){
        if(!active)return;active=false;service.removeLineup(tick);
        service.runLineupWork(()->{if(preview!=null){preview.recycle();preview=null;}if(vision!=null){vision.close();vision=null;}});
    }
}
