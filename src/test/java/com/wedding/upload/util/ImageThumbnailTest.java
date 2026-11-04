package com.wedding.upload.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageThumbnailTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateThumbnailForLargeImage() throws Exception {
        Path imagePath = tempDir.resolve("big.jpg");
        writeLargeJpeg(imagePath, 4000, 3000);

        String thumbnail = ImageThumbnail.create(imagePath);
        assertNotNull(thumbnail, "Thumbnail should be generated for a 4000x3000 image");
        assertTrue(thumbnail.startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void shouldGenerateThumbnailForWebp() throws Exception {
        Path imagePath = copyFixtureToTemp("sample.webp", "photo.webp");

        String thumbnail = ImageThumbnail.create(imagePath);
        assertNotNull(thumbnail, "Thumbnail should be generated for a WebP image");
        assertTrue(thumbnail.startsWith("data:image/jpeg;base64,"));

        Path thumbOut = tempDir.resolve("photo-thumb.jpg");
        boolean saved = ImageThumbnail.save(imagePath, thumbOut);
        assertTrue(saved, "Thumbnail file should be written for a WebP image");
        assertTrue(Files.exists(thumbOut));
    }

    private Path copyFixtureToTemp(String fixture, String target) throws IOException {
        Path dest = tempDir.resolve(target);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertNotNull(in, "Fixture " + fixture + " should exist on the classpath");
            Files.copy(in, dest);
        }
        return dest;
    }

    private void writeLargeJpeg(Path path, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 150, 200));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        for (int x = 0; x < width; x += 400) {
            g.drawLine(x, 0, x, height);
        }
        g.dispose();
        if (!ImageIO.write(img, "jpeg", path.toFile())) {
            throw new IOException("Failed to write test JPEG");
        }
    }
}