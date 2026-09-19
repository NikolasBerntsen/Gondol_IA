package com.gondolia.seed;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Genera la "foto" de una etiqueta con código de barras que un cliente adjunta en un ticket de soporte
 * ("la cámara no lee este código"). Se dibuja píxel a píxel y se codifica como PNG a mano (sin {@code java.awt}),
 * así funciona igual en la imagen Alpine del backend, que no trae fuentes ni librerías gráficas.
 * <p>
 * La etiqueta tiene el EAN-13 real del producto (barras según la codificación estándar), los dígitos en estilo
 * "siete segmentos", la fecha de vencimiento y un reflejo que tapa parte de las barras (por eso no se lee).
 */
final class DemoLabelImage {

    static final int WIDTH = 720;
    static final int HEIGHT = 480;

    private static final String[] L_CODES = {"0001101", "0011001", "0010011", "0111101", "0100011", "0110001",
            "0101111", "0111011", "0110111", "0001011"};
    private static final String[] G_CODES = {"0100111", "0110011", "0011011", "0100001", "0011101", "0111001",
            "0000101", "0010001", "0001001", "0010111"};
    private static final String[] R_CODES = {"1110010", "1100110", "1101100", "1000010", "1011100", "1001110",
            "1010000", "1000100", "1001000", "1110100"};
    private static final String[] PARITY = {"LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLG",
            "LGLGLG", "LGLGGL", "LGGLGL"};

    /** Segmentos encendidos por dígito (a b c d e f g). */
    private static final String[] SEGMENTS = {"abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc",
            "abcdefg", "abcdfg"};

    private final int[] pixels = new int[WIDTH * HEIGHT];

    private DemoLabelImage() {
    }

    /** PNG de la etiqueta del producto con ese EAN-13 y el vencimiento {@code ddMMyyyy} (solo dígitos). */
    static byte[] render(String ean13, String expiryDigits) {
        if (!Ean13.isValid(ean13)) {
            throw new IllegalArgumentException("EAN-13 inválido: " + ean13);
        }
        DemoLabelImage image = new DemoLabelImage();
        image.draw(ean13, expiryDigits);
        return image.encodePng();
    }

    // ------------------------------------------------------------------ dibujo

    private void draw(String ean, String expiryDigits) {
        // Fondo: estante de madera clara con vetas suaves.
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int grain = (int) (8 * Math.sin((x * 0.045) + Math.sin(y * 0.02) * 3.0) + 6 * Math.sin(y * 0.31));
                pixels[y * WIDTH + x] = rgb(196 + grain, 164 + grain, 120 + grain / 2);
            }
        }
        // Sombra y etiqueta.
        fillRect(94, 74, 540, 350, rgb(120, 96, 66));
        fillRect(86, 64, 540, 350, rgb(250, 248, 240));
        // Franja de marca (rojo tomate) y detalle.
        fillRect(86, 64, 540, 70, rgb(186, 52, 40));
        fillRect(86, 134, 540, 6, rgb(244, 197, 66));
        fillRect(110, 84, 120, 30, rgb(250, 238, 220));
        fillRect(246, 90, 260, 18, rgb(232, 170, 150));

        // Vencimiento en siete segmentos: "VTO" como tres bloques y los dígitos.
        fillRect(116, 160, 26, 30, rgb(60, 60, 60));
        fillRect(148, 160, 26, 30, rgb(60, 60, 60));
        fillRect(180, 160, 26, 30, rgb(60, 60, 60));
        int x = 226;
        for (int i = 0; i < expiryDigits.length(); i++) {
            char c = expiryDigits.charAt(i);
            if (Character.isDigit(c)) {
                drawDigit(x, 158, 18, 34, 4, c - '0', rgb(40, 40, 40));
                x += 26;
                if (i == 1 || i == 3) {
                    fillRect(x, 186, 5, 5, rgb(40, 40, 40));
                    x += 12;
                }
            }
        }

        // Código de barras EAN-13.
        String modules = encode(ean);
        int moduleWidth = 4;
        int barsLeft = 86 + (540 - modules.length() * moduleWidth) / 2;
        int barsTop = 220;
        for (int i = 0; i < modules.length(); i++) {
            if (modules.charAt(i) == '1') {
                boolean guard = i < 3 || (i >= 45 && i < 50) || i >= 92;
                fillRect(barsLeft + i * moduleWidth, barsTop, moduleWidth, guard ? 150 : 136, rgb(20, 20, 20));
            }
        }
        // Dígitos bajo las barras.
        drawDigit(barsLeft - 26, barsTop + 142, 14, 26, 3, ean.charAt(0) - '0', rgb(20, 20, 20));
        for (int i = 1; i < 13; i++) {
            int offset = i <= 6 ? 3 * moduleWidth + (i - 1) * 7 * moduleWidth
                    : 50 * moduleWidth + (i - 7) * 7 * moduleWidth;
            drawDigit(barsLeft + offset + 6, barsTop + 142, 14, 26, 3, ean.charAt(i) - '0', rgb(20, 20, 20));
        }

        // Reflejo del flash sobre la mitad derecha de las barras (por eso la cámara no lo lee).
        int cx = barsLeft + (int) (modules.length() * moduleWidth * 0.66);
        int cy = barsTop + 60;
        for (int y = barsTop - 40; y < barsTop + 170; y++) {
            for (int xx = cx - 150; xx < cx + 150; xx++) {
                if (xx < 0 || xx >= WIDTH || y < 0 || y >= HEIGHT) {
                    continue;
                }
                double dx = (xx - cx) / 140.0;
                double dy = (y - cy) / 95.0;
                double d = dx * dx + dy * dy;
                if (d < 1) {
                    double alpha = Math.pow(1 - d, 0.6) * 0.93;
                    blend(xx, y, rgb(255, 255, 250), alpha);
                }
            }
        }
        // Leve desenfoque de movimiento en toda la etiqueta.
        horizontalBlur(80, 60, 560, 370, 2);
    }

    private static String encode(String ean) {
        StringBuilder bits = new StringBuilder("101");
        String parity = PARITY[ean.charAt(0) - '0'];
        for (int i = 1; i <= 6; i++) {
            int digit = ean.charAt(i) - '0';
            bits.append(parity.charAt(i - 1) == 'L' ? L_CODES[digit] : G_CODES[digit]);
        }
        bits.append("01010");
        for (int i = 7; i <= 12; i++) {
            bits.append(R_CODES[ean.charAt(i) - '0']);
        }
        return bits.append("101").toString();
    }

    private void drawDigit(int x, int y, int w, int h, int t, int digit, int color) {
        String on = SEGMENTS[digit];
        int half = h / 2;
        if (on.indexOf('a') >= 0) {
            fillRect(x, y, w, t, color);
        }
        if (on.indexOf('b') >= 0) {
            fillRect(x + w - t, y, t, half, color);
        }
        if (on.indexOf('c') >= 0) {
            fillRect(x + w - t, y + half, t, half, color);
        }
        if (on.indexOf('d') >= 0) {
            fillRect(x, y + h - t, w, t, color);
        }
        if (on.indexOf('e') >= 0) {
            fillRect(x, y + half, t, half, color);
        }
        if (on.indexOf('f') >= 0) {
            fillRect(x, y, t, half, color);
        }
        if (on.indexOf('g') >= 0) {
            fillRect(x, y + half - t / 2, w, t, color);
        }
    }

    private void fillRect(int x, int y, int w, int h, int color) {
        for (int yy = Math.max(0, y); yy < Math.min(HEIGHT, y + h); yy++) {
            for (int xx = Math.max(0, x); xx < Math.min(WIDTH, x + w); xx++) {
                pixels[yy * WIDTH + xx] = color;
            }
        }
    }

    private void blend(int x, int y, int color, double alpha) {
        int current = pixels[y * WIDTH + x];
        int r = (int) (((current >> 16) & 0xFF) * (1 - alpha) + ((color >> 16) & 0xFF) * alpha);
        int g = (int) (((current >> 8) & 0xFF) * (1 - alpha) + ((color >> 8) & 0xFF) * alpha);
        int b = (int) ((current & 0xFF) * (1 - alpha) + (color & 0xFF) * alpha);
        pixels[y * WIDTH + x] = rgb(r, g, b);
    }

    private void horizontalBlur(int x0, int y0, int w, int h, int radius) {
        int[] row = new int[WIDTH];
        for (int y = Math.max(0, y0); y < Math.min(HEIGHT, y0 + h); y++) {
            System.arraycopy(pixels, y * WIDTH, row, 0, WIDTH);
            for (int x = Math.max(radius, x0); x < Math.min(WIDTH - radius, x0 + w); x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = row[x + k];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                int n = radius * 2 + 1;
                pixels[y * WIDTH + x] = rgb(r / n, g / n, b / n);
            }
        }
    }

    private static int rgb(int r, int g, int b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // ------------------------------------------------------------------ PNG

    private byte[] encodePng() {
        byte[] raw = new byte[HEIGHT * (1 + WIDTH * 3)];
        int i = 0;
        for (int y = 0; y < HEIGHT; y++) {
            raw[i++] = 0; // filtro "None"
            for (int x = 0; x < WIDTH; x++) {
                int c = pixels[y * WIDTH + x];
                raw[i++] = (byte) ((c >> 16) & 0xFF);
                raw[i++] = (byte) ((c >> 8) & 0xFF);
                raw[i++] = (byte) (c & 0xFF);
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            compressed.write(buffer, 0, n);
        }
        deflater.end();

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeInt(header, WIDTH);
        writeInt(header, HEIGHT);
        header.write(8);  // bits por canal
        header.write(2);  // RGB
        header.write(0);  // compresión
        header.write(0);  // filtro
        header.write(0);  // sin entrelazado
        chunk(png, "IHDR", header.toByteArray());
        chunk(png, "IDAT", compressed.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, data.length);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
