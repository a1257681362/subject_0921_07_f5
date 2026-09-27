package com.evops.workorder.image;

import com.evops.common.BusinessException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * 图片处理：等比缩放生成缩略图并在底部绘制水印（拍摄时间 + 机手工号）。
 * 纯 JDK 实现（ImageIO + Graphics2D），不依赖外部进程——本机 ImageMagick convert 会崩溃
 * （见工作区 core.* 转储），不可用 shell 兜底。只处理内存中的字节，原图字节不被触碰。
 */
public final class ImageProcessor {

    /** 缩略图处理结果：缩略图字节 + 原图/缩略图尺寸。 */
    public static final class Result {
        private final byte[] thumbBytes;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int thumbWidth;
        private final int thumbHeight;

        Result(byte[] thumbBytes, int sourceWidth, int sourceHeight, int thumbWidth, int thumbHeight) {
            this.thumbBytes = thumbBytes;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.thumbWidth = thumbWidth;
            this.thumbHeight = thumbHeight;
        }

        public byte[] getThumbBytes() { return thumbBytes; }
        public int getSourceWidth() { return sourceWidth; }
        public int getSourceHeight() { return sourceHeight; }
        public int getThumbWidth() { return thumbWidth; }
        public int getThumbHeight() { return thumbHeight; }
    }

    private ImageProcessor() {
    }

    /**
     * 生成带水印缩略图：最长边等比缩到 thumbSize（小图不放大），底部半透明条带上
     * 左对齐绘制水印文本；文本超宽时自动缩小字号（最小 8px），保证水印完整可见。
     *
     * @param sourceBytes   原图字节（只读）
     * @param format        图片格式（jpg/jpeg/png/bmp/gif，缩略图沿用同格式）
     * @param thumbSize     最长边目标像素
     * @param watermarkText 水印文本（拍摄时间 + 机手工号）
     */
    public static Result makeThumbnail(byte[] sourceBytes, String format, int thumbSize, String watermarkText) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
        } catch (IOException e) {
            throw new BusinessException("图片读取失败，文件可能已损坏");
        }
        if (source == null) {
            throw new BusinessException("无法识别的图片内容，仅支持 jpg/png/bmp/gif");
        }

        int srcW = source.getWidth();
        int srcH = source.getHeight();
        double scale = Math.min(1.0, thumbSize / (double) Math.max(srcW, srcH));
        int thumbW = Math.max(1, (int) Math.round(srcW * scale));
        int thumbH = Math.max(1, (int) Math.round(srcH * scale));

        String normalized = normalizeFormat(format);
        boolean alpha = "png".equals(normalized) || "gif".equals(normalized);
        BufferedImage thumb = new BufferedImage(thumbW, thumbH,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, thumbW, thumbH, null);
            drawWatermark(g, thumbW, thumbH, watermarkText);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(thumb, normalized, out)) {
                throw new BusinessException("缩略图编码失败，格式不支持: " + normalized);
            }
        } catch (IOException e) {
            throw new BusinessException("缩略图编码失败: " + e.getMessage());
        }
        return new Result(out.toByteArray(), srcW, srcH, thumbW, thumbH);
    }

    /** 底部半透明黑带 + 白色水印文本；逻辑字体 SANS_SERIF 保证无中文字体环境也能渲染数字与字母。 */
    private static void drawWatermark(Graphics2D g, int width, int height, String text) {
        int padding = Math.max(2, width / 100);
        int fontSize = Math.max(8, Math.min(16, width / 20));
        FontMetrics metrics;
        int fontWidth;
        while (true) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            metrics = g.getFontMetrics();
            fontWidth = metrics.stringWidth(text);
            if (fontWidth <= width - 2 * padding || fontSize <= 8) {
                break;
            }
            fontSize--;
        }
        int bandHeight = metrics.getHeight() + 2 * padding;
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRect(0, height - bandHeight, width, bandHeight);
        g.setColor(Color.WHITE);
        g.drawString(text, padding, height - padding - metrics.getDescent());
    }

    /** 格式归一：jpeg→jpg 的写法统一为 ImageIO 认识的格式名。 */
    public static String normalizeFormat(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if ("jpeg".equals(ext)) {
            return "jpg";
        }
        return ext;
    }
}
