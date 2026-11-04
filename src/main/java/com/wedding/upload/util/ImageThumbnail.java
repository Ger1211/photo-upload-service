package com.wedding.upload.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;

public class ImageThumbnail {

    private static final Logger log = LoggerFactory.getLogger(ImageThumbnail.class);
    private static final int MAX_WIDTH = 400;
    private static final int MAX_DECODE_DIMENSION = 1280;

    public static String create(Path imagePath) {
        BufferedImage thumbnail = generateThumbnail(imagePath);
        if (thumbnail == null) return null;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeJpeg(thumbnail, baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return "data:image/jpeg;base64," + base64;
        } catch (IOException e) {
            log.warn("Failed to encode thumbnail for: {}", imagePath, e);
            return null;
        }
    }

    public static boolean save(Path sourcePath, Path thumbOutputPath) {
        BufferedImage thumbnail = generateThumbnail(sourcePath);
        if (thumbnail == null) return false;

        try {
            Files.createDirectories(thumbOutputPath.getParent());
            try (OutputStream out = Files.newOutputStream(thumbOutputPath)) {
                writeJpeg(thumbnail, out);
            }
            log.debug("Thumbnail saved: {}", thumbOutputPath);
            return true;
        } catch (IOException e) {
            log.warn("Failed to save thumbnail: {} -> {}", sourcePath, thumbOutputPath, e);
            return false;
        }
    }

    private static BufferedImage generateThumbnail(Path imagePath) {
        BufferedImage original = readBounded(imagePath);
        if (original == null) {
            log.warn("Cannot read image: {}", imagePath);
            return null;
        }

        original = fixExifRotation(original, imagePath);

        int width = original.getWidth();
        int height = original.getHeight();

        if (width > MAX_WIDTH) {
            double ratio = (double) MAX_WIDTH / width;
            width = MAX_WIDTH;
            height = (int) (height * ratio);
        }

        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = thumbnail.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, width, height);
        g2d.drawImage(original, 0, 0, width, height, null);
        g2d.dispose();

        return thumbnail;
    }

    /**
     * Decodes an image with a hard cap on the decoded pixel dimensions. Phone
     * photos are typically 3000-5000px wide; decoding them at full resolution can
     * consume ~30-60MB of heap per image and OOM the 512MB heap under concurrent
     * uploads. Subsampling bounds the intermediate bitmap while keeping more than
     * enough detail for a 400px thumbnail.
     */
    private static BufferedImage readBounded(Path imagePath) {
        try (InputStream in = Files.newInputStream(imagePath);
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) return null;

            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) return null;

                ImageReadParam param = reader.getDefaultReadParam();
                int maxDim = Math.max(width, height);
                if (maxDim > MAX_DECODE_DIMENSION) {
                    int subsample = (int) Math.ceil((double) maxDim / MAX_DECODE_DIMENSION);
                    param.setSourceSubsampling(subsample, subsample, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read image for thumbnail: {}", imagePath, e);
            return null;
        }
    }

    private static void writeJpeg(BufferedImage image, OutputStream out) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.75f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage fixExifRotation(BufferedImage image, Path imagePath) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(imagePath.toFile());
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif == null) return image;

            Object orientObj = exif.getObject(ExifIFD0Directory.TAG_ORIENTATION);
            if (orientObj == null) return image;
            int orientation = ((Number) orientObj).intValue();
            if (orientation == 1) return image;

            int w = image.getWidth();
            int h = image.getHeight();

            AffineTransform tx = new AffineTransform();

            switch (orientation) {
                case 2:
                    tx.scale(-1.0, 1.0);
                    tx.translate(-w, 0);
                    break;
                case 3:
                    tx.translate(w, h);
                    tx.rotate(Math.PI);
                    break;
                case 4:
                    tx.scale(1.0, -1.0);
                    tx.translate(0, -h);
                    break;
                case 5:
                    tx.rotate(-Math.PI / 2);
                    tx.scale(-1.0, 1.0);
                    break;
                case 6:
                    tx.translate(h, 0);
                    tx.rotate(Math.PI / 2);
                    break;
                case 7:
                    tx.scale(-1.0, 1.0);
                    tx.translate(-h, 0);
                    tx.translate(0, w);
                    tx.rotate(3 * Math.PI / 2);
                    break;
                case 8:
                    tx.translate(0, w);
                    tx.rotate(3 * Math.PI / 2);
                    break;
                default:
                    return image;
            }

            boolean swap = orientation >= 5;
            int newW = swap ? h : w;
            int newH = swap ? w : h;

            BufferedImage result = new BufferedImage(newW, newH,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = result.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, newW, newH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, tx, null);
            g.dispose();

            return result;

        } catch (Exception e) {
            log.debug("EXIF rotation skipped: {}", e.getMessage());
            return image;
        }
    }
}
