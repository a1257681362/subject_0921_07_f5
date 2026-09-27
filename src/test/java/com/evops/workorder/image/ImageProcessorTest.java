package com.evops.workorder.image;

import com.evops.common.BusinessException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缩略图与水印的纯单元测试：不启 Spring，直接验证图片处理口径。 */
public class ImageProcessorTest {

    @Test
    void landscapeJpg_scalesToLongestSide300() throws Exception {
        byte[] source = solidImage(900, 600, "jpg", Color.GREEN);
        ImageProcessor.Result result =
                ImageProcessor.makeThumbnail(source, "jpg", 300, "2026-09-27 08:30:00 JH0231");

        assertEquals(900, result.getSourceWidth());
        assertEquals(600, result.getSourceHeight());
        assertEquals(300, result.getThumbWidth());
        assertEquals(200, result.getThumbHeight());
        BufferedImage thumb = decode(result.getThumbBytes());
        assertEquals(300, thumb.getWidth());
        assertEquals(200, thumb.getHeight());
    }

    @Test
    void portraitPng_scalesToLongestSide300_andWatermarkBandDrawn() throws Exception {
        byte[] source = solidImage(500, 1200, "png", Color.WHITE);
        ImageProcessor.Result result =
                ImageProcessor.makeThumbnail(source, "png", 300, "2026-09-27 08:30:00 JH0231");

        assertEquals(125, result.getThumbWidth());
        assertEquals(300, result.getThumbHeight());

        BufferedImage thumb = decode(result.getThumbBytes());
        // 水印是底部半透明黑带：纯色白底图上，最底两行应整行明显变暗，顶部保持原色
        int top = thumb.getRGB(10, 10) & 0xFF;
        assertTrue(top > 200, "顶部应保持原图颜色");
        int darkCount = 0;
        for (int x = 0; x < thumb.getWidth(); x++) {
            for (int y = thumb.getHeight() - 2; y < thumb.getHeight(); y++) {
                if ((thumb.getRGB(x, y) & 0xFF) < 120) {
                    darkCount++;
                }
            }
        }
        assertTrue(darkCount > thumb.getWidth(), "底部两行为水印带，应大部分为深色");
    }

    @Test
    void smallImage_notUpscaled() throws Exception {
        byte[] source = solidImage(200, 150, "png", Color.BLUE);
        ImageProcessor.Result result =
                ImageProcessor.makeThumbnail(source, "png", 300, "2026-09-27 08:30:00 JH0231");
        assertEquals(200, result.getThumbWidth());
        assertEquals(150, result.getThumbHeight());
    }

    @Test
    void garbageBytes_rejected() {
        assertThrows(BusinessException.class, () ->
                ImageProcessor.makeThumbnail("not-an-image".getBytes(), "jpg", 300, "wm"));
    }

    /** 生成纯色测试图（供本类与接口层测试共用）。 */
    public static byte[] solidImage(int width, int height, String format, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** 解码图片字节（供本类与接口层测试共用）。 */
    public static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
