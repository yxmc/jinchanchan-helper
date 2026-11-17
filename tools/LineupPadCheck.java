import local.jcc.probe.LineupLayout;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/**
 * 阵容页标定的离线自检：把标定的取样框画到原生截图上，肉眼核对落点。
 *
 * <p>用法：LineupPadCheck 原生截图 输出图 [list|replace]
 *
 * <p>第三个参数区分页面类型，默认 {@code list}：
 * <ul>
 *   <li>{@code list} —— 阵容列表页（三行「应用」）。头像网格与预览框都在这页生效。</li>
 *   <li>{@code replace} —— 收藏上限弹窗页（三行「替换」）。**这页的头像网格与列表页不同**
 *       （K80 实测首卡右移 39、行距 308 而非 324），而且 `LineupVision` 只在 {@code menu}
 *       为真时读取头像、弹窗页 {@code menu} 为假，所以这页不画头像框，只核对标题带与按钮带。</li>
 * </ul>
 */
public class LineupPadCheck {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        boolean replacement = args.length > 2 && args[2].equals("replace");
        LineupLayout.Spec spec = LineupLayout.find(src.getWidth(), src.getHeight());
        if (spec == null) throw new IllegalArgumentException("未登记的尺寸 " + src.getWidth() + "×" + src.getHeight());
        System.out.println("版式：" + spec.label + " " + spec.width + "×" + spec.height +
            "；页面：" + (replacement ? "收藏上限弹窗（替换）" : "阵容列表（应用）"));
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        java.awt.Graphics2D g = out.createGraphics();
        g.setStroke(new java.awt.BasicStroke(3f));
        for (int row = 0; row < 3; row++) {
            int[] title = spec.titleBand(row), button = spec.buttonBand(row);
            // 弹窗页不读头像：把列表页的网格画上去只会造成「标定偏了」的错觉。
            int[] avatar = replacement ? null : spec.avatar(row);
            g.setColor(java.awt.Color.RED);
            g.drawRect(spec.titleLeft, title[0], spec.titleRight - spec.titleLeft, title[1] - title[0]);
            g.setColor(java.awt.Color.GREEN);
            g.drawRect(spec.buttonLeft, button[0], spec.buttonRight - spec.buttonLeft, button[1] - button[0]);
            if (avatar != null) {
                g.setColor(java.awt.Color.CYAN);
                for (int slot = 0; slot < spec.avatarSlots; slot++) {
                    int x = avatar[0] + slot * spec.avatarPitch;
                    if (x + avatar[2] > spec.avatarRight) break;
                    g.drawRect(x, avatar[1], avatar[2], avatar[3]);
                }
                int[] probe = spec.avatarProbe(row);
                g.setColor(java.awt.Color.MAGENTA);
                g.drawRect(probe[0], probe[1], probe[2] - probe[0], probe[3] - probe[1]);
            }
            System.out.println("第 " + (row + 1) + " 行 标题带=" + title[0] + ".." + title[1] +
                " 按钮带=" + button[0] + ".." + button[1] +
                (avatar == null ? " （本页不读头像）" : " 头像框=" + avatar[0] + "," + avatar[1] +
                    " " + avatar[2] + "×" + avatar[3] + " 粗搜=" + spec.avatarProbe(row)[0] + "," +
                    spec.avatarProbe(row)[1] + ".." + spec.avatarProbe(row)[2] + "," + spec.avatarProbe(row)[3]));
        }
        g.setColor(java.awt.Color.YELLOW);
        int[] icon = spec.iconSearch;
        g.drawRect(icon[0], icon[1], icon[2] - icon[0], icon[3] - icon[1]);
        System.out.println("图标搜索区=" + icon[0] + "," + icon[1] + ".." + icon[2] + "," + icon[3] + " 模板边长=" + icon[4]);
        g.dispose();
        ImageIO.write(out, "png", new File(args[1]));
        System.out.println("已写出 " + args[1]);
    }
}
