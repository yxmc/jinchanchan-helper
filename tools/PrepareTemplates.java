package local.jcc.probe;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;

public class PrepareTemplates {
    public static void main(String[] args) throws Exception {
        BufferedImage image = ImageIO.read(Path.of(args[0]).toFile());
        PhoneLayout.Spec spec = PhoneLayout.find(image.getWidth(), image.getHeight());
        if (spec == null) throw new IllegalArgumentException(PhoneLayout.unsupportedMessage());
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        Path directory = Path.of("app/src/main/assets/templates"); Files.createDirectories(directory);
        for (int i = 0; i < 5; i++) Files.write(directory.resolve("name" + i + ".bin"), PhoneVision.mask(pixels, image.getWidth(), spec.name(i)));
        Files.write(directory.resolve("refresh.bin"), PhoneVision.mask(pixels, image.getWidth(), spec.refresh));
        Files.write(directory.resolve("fee1.bin"), PhoneVision.mask(pixels, image.getWidth(), spec.cost(0)));
        Files.write(directory.resolve("fee2.bin"), PhoneVision.mask(pixels, image.getWidth(), spec.cost(4)));
        if (args.length > 1) {
            BufferedImage live = ImageIO.read(Path.of(args[1]).toFile());
            int[] sample = live.getRGB(0, 0, live.getWidth(), live.getHeight(), null, 0, live.getWidth());
            Files.write(directory.resolve("fee3.bin"), PhoneVision.mask(sample, live.getWidth(), spec.cost(4)));
        }
        if (args.length > 2) {
            BufferedImage diana = ImageIO.read(Path.of(args[2]).toFile());
            if (PhoneLayout.find(diana.getWidth(), diana.getHeight()) == null) throw new IllegalArgumentException();
            int[] sample = diana.getRGB(0, 0, diana.getWidth(), diana.getHeight(), null, 0, diana.getWidth());
            Files.write(directory.resolve("name5.bin"), PhoneVision.mask(sample, diana.getWidth(), spec.name(2)));
        }
        System.out.println("Native-pixel reference templates generated.");
    }
}
