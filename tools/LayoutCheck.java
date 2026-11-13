package local.jcc.probe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/**
 * 标定校验：把 PhoneLayout 中某机型的裁剪框与点击点画到原始截图上，用于人工核对。
 *
 * <p>用法：{@code java local.jcc.probe.LayoutCheck <截图> <输出.png>}
 * 截图分辨率必须已在 PhoneLayout 中登记。
 */
public class LayoutCheck {
    private static void mark(Graphics2D g, int[] rect, Color color, String label) {
        g.setColor(color); g.drawRect(rect[0], rect[1], rect[2], rect[3]);
        if (label != null) g.drawString(label, rect[0], rect[1] - 7);
    }
    private static void cross(Graphics2D g, int x, int y, Color color) {
        g.setColor(color);
        g.drawLine(x - 14, y, x + 14, y); g.drawLine(x, y - 14, x, y + 14);
        g.drawOval(x - 9, y - 9, 18, 18);
    }
    public static void main(String[] args) throws Exception {
        BufferedImage image = ImageIO.read(new File(args[0]));
        PhoneLayout.Spec spec = PhoneLayout.find(image.getWidth(), image.getHeight());
        if (spec == null) throw new IllegalArgumentException(
            image.getWidth() + "×" + image.getHeight() + " 未在 PhoneLayout 中登记：" + PhoneLayout.unsupportedMessage());
        Graphics2D g = image.createGraphics();
        g.setStroke(new BasicStroke(3)); g.setFont(new Font("SansSerif", Font.BOLD, 20));
        for (int i = 0; i < 5; i++) {
            mark(g, spec.name(i), Color.CYAN, "name " + (i + 1));
            mark(g, spec.cost(i), Color.YELLOW, null);
            // 点击点：卡片购买与刷新的实际落点
            cross(g, spec.nameX[i] + spec.tapCardDx, spec.tapCardY, Color.RED);
        }
        mark(g, spec.gold, Color.GREEN, "gold");
        mark(g, spec.refresh, Color.ORANGE, "refresh");
        cross(g, spec.tapRefreshX, spec.tapRefreshY, Color.MAGENTA);
        g.dispose();
        ImageIO.write(image, "png", new File(args[1]));
        System.out.println("版式 " + spec.label + "（" + spec.width + "×" + spec.height + "）已标注到 " + args[1]);
    }
}
