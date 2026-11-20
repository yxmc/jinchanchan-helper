package local.jcc.probe;
import java.util.*;
public class LineupCodeCheck {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
  String sample="【阵容码】#林小北-日蚀卡尔玛#MjE5OTY5MDM5MDAxMzM4NDQxNzkyMDMyNDk3MDQ3";
  LineupCode code=LineupCode.parse(sample);check(code.name.equals("林小北-日蚀卡尔玛"),"Extract display name");check(code.text.equals(sample),"Preserve complete clipboard text");
  check(LineupCode.parseAll("分享给你："+sample+"\n"+sample).size()==1,"Deduplicate pasted codes");
  String second="【阵容码】#第二套#MTIzNDU2Nzg=";check(LineupCode.parseAll(sample+"\n"+second).size()==2,"Bulk input");
  boolean rejected=false;try{LineupCode.parse("#坏码#!!!!!!!!");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Reject malformed input");
  rejected=false;try{LineupCode.parse(sample+"\n"+second);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Single selection cannot be multiple codes");
  check(LineupCode.titleMatches("林小北-日蚀卡尔玛","林小北日蚀卡尔码"),"Single OCR substitution in a long title");
  check(!LineupCode.titleMatches("霞洛","霞落"),"Do not fuzzy match short titles");
  check(!LineupCode.titleMatches("林小北-日蚀卡尔玛","另一个完全不同阵容"),"Reject a different lineup title");
  // 游戏会把超长标题截成「前面的字 + …」。旧实现要求两侧等长，导致长名阵容
  // targetMatches 恒假、流程死在 WAIT_IMPORTED/WAIT_APPLY，头像不读、也不左滑。
  String longName="【强化符文专属-暮光试炼】7德玛西亚2神盾使2法师3暗裔";
  check(LineupCode.titleMatches(longName,"【强化符文专属-暮光试炼】7德玛西..."),"Match a truncated long title");
  check(LineupCode.titleMatches(longName,"强化符文专属暮光试炼7德玛西3"),"Match truncated title with one OCR substitution");
  check(!LineupCode.titleMatches(longName,"【夜市征召-暗影岛95】3暗影岛3神盾使"),"Truncation rule must not match another lineup");
  check(!LineupCode.titleMatches(longName,"强化符文"),"Reject a too-short prefix");
  System.out.println("Lineup code checks passed.");
 }
}
