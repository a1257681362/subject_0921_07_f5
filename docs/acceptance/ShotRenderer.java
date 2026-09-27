import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 终端风格截图渲染器：标题栏 + 内容区，输出 PNG。
 * 用法: java ShotRenderer <标题> <输入文本> <输出png> <常规字体ttf> <粗体ttf>
 * 说明: 环境里的 ImageMagick label: 渲染不稳定，这里用 JDK 自带 AWT 绘制，零外部依赖。
 */
public class ShotRenderer {

    private static final Color TITLE_BG = new Color(0x33, 0x33, 0x4d);
    private static final Color TITLE_FG = new Color(0xff, 0xff, 0xff);
    private static final Color BODY_BG = new Color(0x22, 0x22, 0x2e);
    private static final Color BODY_FG = new Color(0xe6, 0xe6, 0xe6);
    private static final int PAD = 14;
    private static final int TITLE_SIZE = 18;
    private static final int BODY_SIZE = 14;

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: ShotRenderer <title> <input> <output.png> <regular.ttf> <bold.ttf>");
            System.exit(1);
        }
        String title = args[0];
        List<String> lines = Files.readAllLines(new File(args[1]).toPath(), StandardCharsets.UTF_8);
        File output = new File(args[2]);
        Font bodyFont = Font.createFont(Font.TRUETYPE_FONT, new File(args[3]))
                .deriveFont(Font.PLAIN, (float) BODY_SIZE);
        Font titleFont = Font.createFont(Font.TRUETYPE_FONT, new File(args[4]))
                .deriveFont(Font.PLAIN, (float) TITLE_SIZE);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        pg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        FontMetrics titleMetrics = pg.getFontMetrics(titleFont);
        FontMetrics bodyMetrics = pg.getFontMetrics(bodyFont);
        int titleBarHeight = titleMetrics.getHeight() + PAD;
        int lineHeight = bodyMetrics.getHeight() + 3;

        int maxWidth = titleMetrics.stringWidth("  " + title);
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, bodyMetrics.stringWidth(line));
        }
        pg.dispose();

        int width = Math.max(420, maxWidth + PAD * 2 + 8);
        int height = titleBarHeight + Math.max(1, lines.size()) * lineHeight + PAD * 2;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        g.setColor(TITLE_BG);
        g.fillRect(0, 0, width, titleBarHeight);
        g.setColor(BODY_BG);
        g.fillRect(0, titleBarHeight, width, height - titleBarHeight);

        g.setFont(titleFont);
        g.setColor(TITLE_FG);
        g.drawString("  " + title, PAD - 4, titleBarHeight - PAD / 2 - titleMetrics.getDescent());

        g.setFont(bodyFont);
        g.setColor(BODY_FG);
        int y = titleBarHeight + PAD + bodyMetrics.getAscent();
        for (String line : lines) {
            g.drawString(line, PAD, y);
            y += lineHeight;
        }
        g.dispose();

        ImageIO.write(image, "png", output);
        System.out.println("已生成 " + output);
    }
}
