package com.evops.workorder.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 缩略图处理器：等比缩放到最长边 thumbMaxSide，右下角叠水印（拍摄时间 + 机手工号）。
 * 纯 JDK AWT/ImageIO 实现，无外部依赖；中文字体打包在 classpath:fonts/ 下，
 * 服务器无需安装中文字体。只处理缩略图，原图不动。
 */
@Component
public class ThumbnailProcessor {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailProcessor.class);

    /** 打包进 jar 的中文字体（Noto Sans SC），找不到则退回逻辑字体（中文可能缺字）。 */
    private static final String BUNDLED_FONT = "fonts/NotoSansSC-Regular.ttf";

    private Font baseFont;

    @PostConstruct
    void loadFont() {
        try (InputStream in = new ClassPathResource(BUNDLED_FONT).getInputStream()) {
            baseFont = Font.createFont(Font.TRUETYPE_FONT, in);
            log.info("水印字体加载成功: {}", BUNDLED_FONT);
        } catch (Exception e) {
            log.warn("水印字体 {} 加载失败，退回系统默认字体: {}", BUNDLED_FONT, e.getMessage());
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
    }

    /** 处理结果：缩略图字节 + 实际尺寸。 */
    public static class Result {
        public final byte[] bytes;
        public final int width;
        public final int height;

        Result(byte[] bytes, int width, int height) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 生成带水印缩略图。
     *
     * @param source        原图（已解码）
     * @param maxSide       最长边像素，等比缩放，小图不放大
     * @param watermarkLine 水印文本（拍摄时间 + 机手工号）
     * @param formatName    ImageIO 输出格式（jpg/png）
     */
    public Result process(BufferedImage source, int maxSide, String watermarkLine, String formatName) throws IOException {
        int sw = source.getWidth();
        int sh = source.getHeight();
        double scale = Math.min(1.0, maxSide / (double) Math.max(sw, sh));
        int tw = Math.max(1, (int) Math.round(sw * scale));
        int th = Math.max(1, (int) Math.round(sh * scale));

        boolean jpeg = "jpg".equalsIgnoreCase(formatName) || "jpeg".equalsIgnoreCase(formatName);
        BufferedImage thumb = new BufferedImage(tw, th,
                jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = thumb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (jpeg) {
                // JPEG 无透明通道，先铺白底，避免透明 PNG 转出黑底
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, tw, th);
            }
            g.drawImage(source, 0, 0, tw, th, null);
            drawWatermark(g, tw, th, watermarkLine);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(thumb, jpeg ? "jpg" : formatName, out);
        return new Result(out.toByteArray(), tw, th);
    }

    /** 右下角水印：半透明底条 + 白字，字号随图宽自适应。 */
    private void drawWatermark(Graphics2D g, int width, int height, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int fontSize = Math.max(11, width / 22);
        Font font = baseFont.deriveFont(Font.PLAIN, (float) fontSize);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int padX = Math.max(4, fontSize / 3);
        int padY = Math.max(2, fontSize / 5);
        int barWidth = textWidth + padX * 2;
        int barHeight = fm.getHeight() + padY * 2;
        int x = width - barWidth - padX;
        int y = height - barHeight - padY;
        if (x < 0 || y < 0) {
            // 图太小放不下底条时直接贴右下角写字
            x = 0;
            y = height - barHeight;
        }

        g.setComposite(AlphaComposite.SrcOver.derive(0.55f));
        g.setColor(Color.BLACK);
        g.fillRect(x, y, barWidth, barHeight);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(Color.WHITE);
        g.drawString(text, x + padX, y + padY + fm.getAscent());
    }
}
