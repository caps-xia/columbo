package org.caps.columbo.infrastructure.adapter.port;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 贴图识别超时回归（2026-09-14 线上：retina 大截图识别 30s 超时降级）：
 * 只有超过 4096 的病态巨图才降采样——low 思考档下 OCR 精度靠原始分辨率
 * （2880px 逐字精确、压到 1568px 小字读串），常规截图必须原样透传。
 */
class ImageUnderstandingPortImplDownscaleTest {

    @Test
    void downscalesOnlyPathologicalImages() throws Exception {
        byte[] huge = png(5000, 3200, "ERROR OrderController:88");
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(huge);

        String result = ImageUnderstandingPortImpl.downscaleDataUrl(dataUrl);

        assertTrue(result.startsWith("data:image/png;base64,"));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(result.substring(result.indexOf(',') + 1))));
        assertEquals(ImageUnderstandingPortImpl.MAX_SIDE, Math.max(image.getWidth(), image.getHeight()));
    }

    @Test
    void keepsRetinaScreenshotUntouched() throws Exception {
        // 常规 retina 全屏截图（2880x1800）不许降采样：小字会被识别读串
        byte[] retina = png(2880, 1800, "retina");
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(retina);

        assertEquals(dataUrl, ImageUnderstandingPortImpl.downscaleDataUrl(dataUrl));
    }

    @Test
    void keepsSmallImageUntouched() throws Exception {
        byte[] small = png(800, 600, "small");
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(small);

        assertEquals(dataUrl, ImageUnderstandingPortImpl.downscaleDataUrl(dataUrl));
    }

    @Test
    void passesThroughUndecodableAndMalformedInput() {
        // 非 base64 图片内容：解码或 ImageIO 失败时不抛异常、原样返回
        String broken = "data:image/png;base64," + Base64.getEncoder().encodeToString("not an image".getBytes());
        assertEquals(broken, ImageUnderstandingPortImpl.downscaleDataUrl(broken));
        assertEquals("data:image/png;base64,", ImageUnderstandingPortImpl.downscaleDataUrl("data:image/png;base64,"));
        assertSame("plain-text", ImageUnderstandingPortImpl.downscaleDataUrl("plain-text"));
        assertSame(null, ImageUnderstandingPortImpl.downscaleDataUrl(null));
    }

    private static byte[] png(int width, int height, String text) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            graphics.drawString(text, 20, 60);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
