import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 图片验证小工具（验收脚本用）：
 *   make <输出路径> <宽> <高> <标签>   生成一张带色块与标签的测试 JPEG（模拟田间照片）
 *   size <图片路径>                    打印图片尺寸，格式 WxH
 */
public class ImageVerifyTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: make <path> <w> <h> <label> | size <path>");
            System.exit(2);
        }
        if ("make".equals(args[0])) {
            int w = Integer.parseInt(args[2]);
            int h = Integer.parseInt(args[3]);
            String label = args.length > 4 ? args[4] : "field";
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(0x6B, 0x8E, 0x23));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(0x8B, 0x45, 0x13));
            g.fillRect(0, h * 2 / 3, w, h / 3);
            g.setColor(new Color(0x87, 0xCE, 0xEB));
            g.fillRect(0, 0, w, h / 4);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(16, w / 30)));
            g.drawString(label, w / 20, h / 2);
            g.dispose();
            File out = new File(args[1]);
            out.getParentFile().mkdirs();
            ImageIO.write(image, "jpg", out);
            System.out.println("MADE " + out.getAbsolutePath() + " " + w + "x" + h);
        } else if ("size".equals(args[0])) {
            BufferedImage image = ImageIO.read(new File(args[1]));
            if (image == null) {
                System.err.println("NOT_AN_IMAGE " + args[1]);
                System.exit(1);
            }
            System.out.println(image.getWidth() + "x" + image.getHeight());
        } else {
            System.err.println("unknown mode: " + args[0]);
            System.exit(2);
        }
    }
}
