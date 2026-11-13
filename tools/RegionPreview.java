package local.jcc.probe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** Draw the exact phone-specific crop rectangles on an unmodified-size diagnostic copy. */
public class RegionPreview {
    private static void mark(Graphics2D g, int[] rect, Color color, String label) {
        g.setColor(color); g.drawRect(rect[0], rect[1], rect[2], rect[3]);
        g.drawString(label, rect[0], rect[1] - 7);
    }
    public static void main(String[] args) throws Exception {
        BufferedImage image = ImageIO.read(new File(args[0]));
        PhoneLayout.Spec spec = PhoneLayout.find(image.getWidth(), image.getHeight());
        if (spec == null) throw new IllegalArgumentException(PhoneLayout.unsupportedMessage());
        Graphics2D g = image.createGraphics(); g.setStroke(new BasicStroke(3)); g.setFont(new Font("SansSerif", Font.BOLD, 20));
        for (int i = 0; i < 5; i++) {
            mark(g, spec.name(i), Color.CYAN, "name " + (i + 1));
            mark(g, spec.cost(i), Color.YELLOW, "fee");
        }
        mark(g, spec.gold, Color.GREEN, "gold"); mark(g, spec.refresh, Color.CYAN, "refresh");
        g.dispose(); ImageIO.write(image, "png", new File(args[1]));
    }
}
