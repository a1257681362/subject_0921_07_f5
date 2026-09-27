package com.evops.workorder.photo;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缩略图处理器：尺寸口径、水印落墨、小图不放大。 */
class ThumbnailProcessorTest {

    private final ThumbnailProcessor processor = new ThumbnailProcessor();

    {
        processor.loadFont();
    }

    @Test
    void landscapeScalesToMaxSide() throws Exception {
        BufferedImage source = solidImage(1200, 800, Color.WHITE);
        ThumbnailProcessor.Result result = processor.process(source, 300, "拍摄时间:2026-09-27 10:30:00 机手:A017", "jpg");
        assertEquals(300, result.width);
        assertEquals(200, result.height);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes));
        assertEquals(300, decoded.getWidth());
        assertEquals(200, decoded.getHeight());
    }

    @Test
    void portraitScalesToMaxSide() throws Exception {
        BufferedImage source = solidImage(600, 1200, Color.WHITE);
        ThumbnailProcessor.Result result = processor.process(source, 300, "w", "png");
        assertEquals(150, result.width);
        assertEquals(300, result.height);
    }

    @Test
    void smallImageNotUpscaled() throws Exception {
        BufferedImage source = solidImage(200, 100, Color.WHITE);
        ThumbnailProcessor.Result result = processor.process(source, 300, "w", "jpg");
        assertEquals(200, result.width);
        assertEquals(100, result.height);
    }

    @Test
    void watermarkDrawnInBottomRightCorner() throws Exception {
        BufferedImage source = solidImage(900, 600, Color.WHITE);
        ThumbnailProcessor.Result withMark = processor.process(source, 300, "拍摄时间:2026-09-27 10:30:00 机手:A017", "jpg");
        ThumbnailProcessor.Result withoutMark = processor.process(source, 300, "", "jpg");

        BufferedImage marked = ImageIO.read(new ByteArrayInputStream(withMark.bytes));
        BufferedImage plain = ImageIO.read(new ByteArrayInputStream(withoutMark.bytes));

        // 右下角水印区应与无水印版本有差异（底条+文字落墨）
        int diff = 0;
        for (int x = marked.getWidth() / 2; x < marked.getWidth(); x++) {
            for (int y = marked.getHeight() - 40; y < marked.getHeight(); y++) {
                if (marked.getRGB(x, y) != plain.getRGB(x, y)) {
                    diff++;
                }
            }
        }
        assertTrue(diff > 100, "右下角水印区应有明显落墨，实际差异像素 " + diff);

        // 左上角远离水印区，两图应基本一致
        assertEquals(plain.getRGB(5, 5), marked.getRGB(5, 5));
    }

    @Test
    void watermarkTextDiffersPerContent() throws Exception {
        BufferedImage source = solidImage(900, 600, Color.WHITE);
        ThumbnailProcessor.Result a = processor.process(source, 300, "拍摄时间:2026-09-27 10:30:00 机手:A017", "jpg");
        ThumbnailProcessor.Result b = processor.process(source, 300, "拍摄时间:2026-09-28 08:00:00 机手:B992", "jpg");
        BufferedImage ia = ImageIO.read(new ByteArrayInputStream(a.bytes));
        BufferedImage ib = ImageIO.read(new ByteArrayInputStream(b.bytes));
        assertNotEquals(ia.getRGB(ia.getWidth() - 20, ia.getHeight() - 10),
                ib.getRGB(ib.getWidth() - 20, ib.getHeight() - 10),
                "不同水印文本应产生不同落墨");
    }

    private BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }
}
