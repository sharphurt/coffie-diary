package ru.coffeediary;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

final class PhotoImages {
  static final int MAX_BYTES = 10 * 1024 * 1024;

  static void write(byte[] input, Path full, Path thumb) throws IOException {
    if (input.length == 0 || input.length > MAX_BYTES) throw invalid();
    try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw invalid();
      var reader = readers.next();
      try {
        if (!Set.of("jpeg", "png", "webp").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))
          throw invalid();
        reader.setInput(stream, true, true);
        if ((long) reader.getWidth(0) * reader.getHeight(0) > 40_000_000L) throw invalid();
        BufferedImage source = reader.read(0);
        int orientation = orientation(input);
        encode(source, orientation, 2560, full);
        encode(source, orientation, 480, thumb);
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof Rules.Invalid invalid) throw invalid;
      throw invalid();
    }
  }

  private static int orientation(byte[] input) throws IOException {
    try {
      var exif = ImageMetadataReader.readMetadata(new ByteArrayInputStream(input))
          .getFirstDirectoryOfType(ExifIFD0Directory.class);
      Integer orientation = exif == null ? null : exif.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
      return orientation == null || orientation < 1 || orientation > 8 ? 1 : orientation;
    } catch (com.drew.imaging.ImageProcessingException e) {
      throw new IOException(e);
    }
  }

  private static void encode(BufferedImage source, int orientation, int max, Path path)
      throws IOException {
    int w = source.getWidth(), h = source.getHeight();
    double ratio = Math.min(1, (double) max / Math.max(w, h));
    int sw = Math.max(1, (int) Math.round(w * ratio));
    int sh = Math.max(1, (int) Math.round(h * ratio));
    boolean swapped = orientation >= 5;
    var output = new BufferedImage(swapped ? sh : sw, swapped ? sw : sh, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = output.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      switch (orientation) {
        case 2 -> { g.translate(sw, 0); g.scale(-1, 1); }
        case 3 -> { g.translate(sw, sh); g.rotate(Math.PI); }
        case 4 -> { g.translate(0, sh); g.scale(1, -1); }
        case 5 -> { g.rotate(Math.PI / 2); g.scale(1, -1); }
        case 6 -> { g.translate(sh, 0); g.rotate(Math.PI / 2); }
        case 7 -> { g.translate(sh, sw); g.rotate(Math.PI / 2); g.scale(-1, 1); }
        case 8 -> { g.translate(0, sw); g.rotate(-Math.PI / 2); }
        default -> {}
      }
      g.drawImage(source, 0, 0, sw, sh, null);
    } finally {
      g.dispose();
    }
    if (!ImageIO.write(output, "png", path.toFile())) throw new IOException("PNG encoder missing");
  }

  private static Rules.Invalid invalid() {
    return new Rules.Invalid("file", "Выберите JPEG, PNG или WebP до 10 МиБ и 40 мегапикселей");
  }

  private PhotoImages() {}
}
