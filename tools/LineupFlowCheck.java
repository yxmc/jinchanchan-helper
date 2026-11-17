package local.jcc.probe;
import static local.jcc.probe.LineupFlow.Action.*;
public class LineupFlowCheck {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
  LineupFlow f=new LineupFlow();LineupFlow.View v=new LineupFlow.View();v.board=true;v.shop=true;
  check(f.next(v)==CLOSE_SHOP,"Close an open shop using total gold first");check(f.next(v)==WAIT,"Never toggle gold repeatedly on the old open frame");
  v.shop=false;check(f.next(v)==OPEN_MENU,"Open formation only after shop closes");check(f.next(v)==WAIT,"Do not click import before the menu appears");
  v.menu=true;check(f.next(v)==IMPORT,"Use the recognized import button");
  v.menu=false;v.replacement=true;check(f.next(v)==WAIT,"First replacement frame is not stable");check(f.next(v)==REPLACE_THIRD,"Replace only the third row after confirmation");
  check(f.next(v)==WAIT,"Never re-click replace while transition is pending");v.replacement=false;v.menu=true;
  check(f.next(v)==WAIT,"A different imported title must not be applied");v.targetMatches=true;
  check(f.next(v)==WAIT,"Verify imported row before applying");check(f.next(v)==APPLY_TARGET,"Apply only the expected stable target lineup");v.menu=false;
  check(f.next(v)==WAIT,"Closing the panel alone does not prove application");v.menu=true;v.applied=true;
  check(f.next(v)==CLOSE_EXISTING,"Confirm application via Cancel state then close");v.menu=false;
  check(f.next(v)==FINISH,"Complete on the game board after confirmed application");
  f=new LineupFlow();v=new LineupFlow.View();v.menu=true;check(f.next(v)==CLOSE_EXISTING,"Reset an existing menu before importing new clipboard data");
  f=new LineupFlow();v=new LineupFlow.View();v.board=true;check(f.next(v)==OPEN_MENU,"Open from closed board");
  v.menu=true;v.full=true;v.targetMatches=true;check(f.next(v)==IMPORT,"Import with full favorites");
  for(int i=0;i<8;i++)check(f.next(v)==WAIT,"An old same-title third row must not bypass the required replacement dialog");
  // 替换按钮某一帧 OCR 漏字时的回退：必须能重新给出 REPLACE_THIRD，否则重试永远不生效。
  // 用正常流程走到「已发出 IMPORT」：棋盘 -> 打开阵容 -> 导入。
  f=new LineupFlow();v=new LineupFlow.View();v.board=true;
  check(f.next(v)==OPEN_MENU,"Open formation from the board");
  v.menu=true;v.full=true;check(f.next(v)==IMPORT,"Import with a full favorites list");
  v.menu=false;v.replacement=true;
  check(f.next(v)==WAIT,"Replacement dialog needs two stable frames");check(f.next(v)==REPLACE_THIRD,"Emit replace once stable");
  check(f.next(v)==WAIT,"After emitting replace the flow waits for the menu");
  f.rewind(REPLACE_THIRD);
  check(f.stage()==LineupFlow.Stage.WAIT_IMPORTED,"Rewind must return to the import step");
  check(f.next(v)==WAIT,"Rewound replacement still needs two stable frames");
  check(f.next(v)==REPLACE_THIRD,"Rewound flow must emit replace again");
  // 应用按钮漏字时的回退：回到 WAIT_APPLY 重新累计两帧确认。
  f=new LineupFlow();v=new LineupFlow.View();v.board=true;f.next(v);
  v.menu=true;v.full=false;v.targetMatches=false;f.next(v);
  v.menu=false;v.replacement=true;f.next(v);f.next(v);
  v.replacement=false;v.menu=true;v.targetMatches=true;
  check(f.next(v)==WAIT,"Verify imported row before applying");
  check(f.next(v)==APPLY_TARGET,"Apply after two stable matching frames");
  f.rewind(APPLY_TARGET);
  check(f.stage()==LineupFlow.Stage.WAIT_APPLY,"Rewind apply must return to verification");
  check(f.next(v)==WAIT,"Rewound apply re-verifies before clicking");
  check(f.next(v)==APPLY_TARGET,"Rewound flow must emit apply again");
  // 应用成功后标题整行读不出来时不能死等：实测真机 27 帧全是 title=false 而 applied=true，
  // 面板早就应用好了却空等 15 秒才超时。已确认过的行 + 已应用即可关闭面板。
  f=new LineupFlow();v=new LineupFlow.View();v.board=true;f.next(v);
  v.menu=true;v.full=false;v.targetMatches=false;f.next(v);
  v.menu=false;v.replacement=true;f.next(v);f.next(v);
  v.replacement=false;v.menu=true;v.targetMatches=true;
  check(f.next(v)==WAIT,"Row must be verified twice before applying");
  check(f.next(v)==APPLY_TARGET,"Apply after row is confirmed");
  v.applied=true;v.targetMatches=false;v.menu=true;
  check(f.next(v)==CLOSE_EXISTING,"Applied row closes the panel even when the title OCR fails");
  // 但「没确认过的行」不能只凭 applied 就关闭：其它行同样可能处于已应用状态。
  f=new LineupFlow();v=new LineupFlow.View();v.board=true;f.next(v);
  v.menu=true;v.full=false;v.targetMatches=false;
  check(f.next(v)==IMPORT,"Open import for an unverified row");
  v.applied=true;
  for(int i=0;i<4;i++)check(f.next(v)==WAIT,"An unverified row never applies or closes");
  System.out.println("Lineup flow checks passed.");
 }
}
