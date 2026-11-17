import local.jcc.probe.PhoneLayout;
import local.jcc.probe.PhoneVision;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;

/**
 * 从平板原生截图采集卡名字模。
 *
 * <p>用法：PadTemplates 截图路径 卡名1 卡名2 ...
 * 按标定版式裁出 5 个卡名框，把每个卡名的白字掩码写成
 * app/src/main/assets/templates-pad/nameN.bin，并按顺序生成 manifest.txt。
 *
 * <p>只接受该机型原生分辨率的截图——缩略图或 JPEG 放大后生成的字模笔画会错位。
 */
public class PadTemplates {
    public static void main(String[] args) throws Exception {
        Path shot = Path.of(args[0]);
        BufferedImage image = ImageIO.read(shot.toFile());
        PhoneLayout.Spec spec = PhoneLayout.find(image.getWidth(), image.getHeight());
        if (spec == null) throw new IllegalArgumentException("截图分辨率不在支持范围：" + image.getWidth() + "×" + image.getHeight());
        System.out.println("版面：" + spec.label + " " + spec.width + "×" + spec.height + "  字模目录：" + spec.templateDir);
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        Path dir = Path.of("app/src/main/assets/" + spec.templateDir);
        Files.createDirectories(dir);
        StringBuilder manifest = new StringBuilder();
        int written = 0;
        for (int i = 0; i < 5 && i + 1 < args.length; i++) {
            String name = args[i + 1].trim();
            if (name.isEmpty()) continue;
            byte[] mask = PhoneVision.mask(pixels, image.getWidth(), spec.name(i));
            int ink = 0;
            for (byte b : mask) if (b != 0) ink++;
            if (ink < 60) { System.out.println("跳过第 " + (i + 1) + " 格「" + name + "」：掩码墨迹只有 " + ink); continue; }
            Files.write(dir.resolve("name" + written + ".bin"), mask);
            manifest.append(name).append('\n');
            System.out.println("第 " + (i + 1) + " 格 → name" + written + ".bin「" + name + "」墨迹 " + ink);
            written++;
        }
        System.out.println("已写入 " + written + " 个卡名字模到 " + dir);
        System.out.println("清单内容：\n" + manifest);
    }
}
