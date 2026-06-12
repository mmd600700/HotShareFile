package com.hotsharefile.hotsharefile;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

public class QR {

    public static Bitmap GenerateQrCode(int targetSize, String text) {
        QrEncoder encoder = new QrEncoder();
        return encoder.encode(targetSize, text);
    }

    private static class QrEncoder {

        // Precomputed Galois Field tables for Reed-Solomon error correction.
        // Made static to avoid recalculation on every QR generation.
        private static final int[] EXP = new int[512];
        private static final int[] LOG = new int[256];

        static {
            int x = 1;
            for (int i = 0; i < 255; i++) {
                EXP[i] = x;
                LOG[x] = i;
                x <<= 1;
                if ((x & 0x100) != 0) x ^= 0x11D; // QR Code primitive polynomial
            }
            for (int i = 255; i < 512; i++) {
                EXP[i] = EXP[i - 255];
            }
        }

        private int gfMultiply(int x, int y) {
            if (x == 0 || y == 0) return 0;
            return EXP[LOG[x] + LOG[y]];
        }

        private int[] getGenerator(int degree) {
            int[] gen = {1};
            for (int i = 0; i < degree; i++) {
                int[] nextGen = new int[gen.length + 1];
                nextGen[0] = gen[0];
                for (int j = 1; j < gen.length; j++) {
                    nextGen[j] = gen[j] ^ gfMultiply(gen[j - 1], EXP[i]);
                }
                nextGen[gen.length] = gfMultiply(gen[gen.length - 1], EXP[i]);
                gen = nextGen;
            }
            return gen;
        }

        private int[] calculateEcc(int[] data, int[] gen) {
            int[] msg = new int[data.length + gen.length - 1];
            System.arraycopy(data, 0, msg, 0, data.length);
            for (int i = 0; i < data.length; i++) {
                int factor = msg[i];
                if (factor == 0) continue;
                for (int j = 0; j < gen.length; j++) {
                    msg[i + j] ^= gfMultiply(gen[j], factor);
                }
            }
            int[] ecc = new int[gen.length - 1];
            System.arraycopy(msg, data.length, ecc, 0, ecc.length);
            return ecc;
        }

        private void drawFinder(int[][] matrix, int startX, int startY) {
            for (int y = -1; y <= 7; y++) {
                for (int x = -1; x <= 7; x++) {
                    int cx = startX + x;
                    int cy = startY + y;
                    if (cx < 0 || cx >= 33 || cy < 0 || cy >= 33) continue;

                    if (x == -1 || x == 7 || y == -1 || y == 7) {
                        matrix[cy][cx] = 0;
                    } else if (x == 0 || x == 6 || y == 0 || y == 6) {
                        matrix[cy][cx] = 1;
                    } else if (x == 1 || x == 5 || y == 1 || y == 5) {
                        matrix[cy][cx] = 0;
                    } else {
                        matrix[cy][cx] = 1;
                    }
                }
            }
        }

        public Bitmap encode(int tSize, String content) {
            byte[] rawData = content.getBytes(StandardCharsets.UTF_8);
            if (rawData.length > 62) {
                throw new IllegalArgumentException("Text exceeds Version 4-M capacity (62 bytes).");
            }

            boolean[] bits = new boolean[512];
            int bitPos = 0;

            // Byte Mode Indicator (0100)
            bits[bitPos++] = false; bits[bitPos++] = true;
            bits[bitPos++] = false; bits[bitPos++] = false;

            // Length Indicator (8 bits for Version 4)
            int len = rawData.length;
            for (int i = 7; i >= 0; i--) {
                bits[bitPos++] = ((len >> i) & 1) == 1;
            }

            // Data Bits
            for (byte b : rawData) {
                for (int i = 7; i >= 0; i--) {
                    bits[bitPos++] = ((b >> i) & 1) == 1;
                }
            }

            // Terminator (Up to 4 zeros)
            int terminatorLimit = Math.min(4, 512 - bitPos);
            for (int i = 0; i < terminatorLimit; i++) {
                bits[bitPos++] = false;
            }

            // Byte Alignment Padding
            while (bitPos % 8 != 0) {
                bits[bitPos++] = false;
            }

            // Padding Bytes (0xEC and 0x11)
            boolean padSwitch = true;
            while (bitPos < 512) {
                int padByte = padSwitch ? 0xEC : 0x11;
                for (int i = 7; i >= 0; i--) {
                    bits[bitPos++] = ((padByte >> i) & 1) == 1;
                }
                padSwitch = !padSwitch;
            }

            // Extract Bytes
            int[] dataWords = new int[64];
            for (int i = 0; i < 64; i++) {
                int b = 0;
                for (int j = 0; j < 8; j++) {
                    if (bits[i * 8 + j]) b |= (1 << (7 - j));
                }
                dataWords[i] = b;
            }

            // Split into 2 blocks for Version 4-M
            int[] block1Data = new int[32];
            int[] block2Data = new int[32];
            System.arraycopy(dataWords, 0, block1Data, 0, 32);
            System.arraycopy(dataWords, 32, block2Data, 0, 32);

            int[] gen = getGenerator(18);
            int[] block1Ecc = calculateEcc(block1Data, gen);
            int[] block2Ecc = calculateEcc(block2Data, gen);

            // Interleave Data and ECC
            int[] finalData = new int[100];
            int idx = 0;
            for (int i = 0; i < 32; i++) {
                finalData[idx++] = block1Data[i];
                finalData[idx++] = block2Data[i];
            }
            for (int i = 0; i < 18; i++) {
                finalData[idx++] = block1Ecc[i];
                finalData[idx++] = block2Ecc[i];
            }

            int[][] matrix = new int[33][33];
            for (int y = 0; y < 33; y++) {
                for (int x = 0; x < 33; x++) matrix[y][x] = -1;
            }

            // Patterns
            drawFinder(matrix, 0, 0);
            drawFinder(matrix, 26, 0);
            drawFinder(matrix, 0, 26);

            // Alignment Pattern for Version 4 at (26, 26)
            for (int y = -2; y <= 2; y++) {
                for (int x = -2; x <= 2; x++) {
                    boolean isBlack = (x == -2 || x == 2 || y == -2 || y == 2 || (x == 0 && y == 0));
                    matrix[26 + y][26 + x] = isBlack ? 1 : 0;
                }
            }

            // Timing Patterns
            for (int i = 8; i < 25; i++) {
                matrix[6][i] = (i % 2 == 0) ? 1 : 0;
                matrix[i][6] = (i % 2 == 0) ? 1 : 0;
            }

            // Dark Module
            matrix[25][8] = 1;

            // Reserve Format Info Area
            for (int i = 0; i < 9; i++) {
                if (matrix[8][i] == -1) matrix[8][i] = 2;
                if (matrix[i][8] == -1) matrix[i][8] = 2;
            }
            for (int i = 25; i < 33; i++) {
                if (matrix[8][i] == -1) matrix[8][i] = 2;
                if (matrix[i][8] == -1) matrix[i][8] = 2;
            }

            // Data Placement (ZigZag)
            int bitIdx = 0;
            int dir = -1;
            int x = 32;
            int y = 32;
            while (x > 0) {
                if (x == 6) x--;
                while (y >= 0 && y < 33) {
                    for (int i = 0; i < 2; i++) {
                        int cx = x - i;
                        if (matrix[y][cx] == -1) {
                            boolean bit = false;
                            if (bitIdx < 800) {
                                bit = ((finalData[bitIdx / 8] >> (7 - (bitIdx % 8))) & 1) == 1;
                            }
                            // Apply Mask 0: (row + col) % 2 == 0
                            boolean maskBit = ((y + cx) % 2 == 0);
                            matrix[y][cx] = (bit ^ maskBit) ? 1 : 0;
                            bitIdx++;
                        }
                    }
                    y += dir;
                }
                dir = -dir;
                y += dir;
                x -= 2;
            }

            // Format Info Bits Hardcoded (Level M, Mask 0 -> 0x5412)
            // LSB -> MSB sequence exact standard match
            int[] formatBits = {0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1};

            int[] xs1 = {8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0};
            int[] ys1 = {0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8};

            int[] xs2 = {8, 8, 8, 8, 8, 8, 8, 25, 26, 27, 28, 29, 30, 31, 32};
            int[] ys2 = {32, 31, 30, 29, 28, 27, 26, 8, 8, 8, 8, 8, 8, 8, 8};

            for (int i = 0; i < 15; i++) {
                matrix[ys1[i]][xs1[i]] = formatBits[i];
                matrix[ys2[i]][xs2[i]] = formatBits[i];
            }

            // --- PERFECT PIXEL RENDERING (NO CANVAS FLOAT BLUR) ---
            int margin = 4;
            int matrixSize = 33;
            int totalModules = matrixSize + (2 * margin);

            // Calculate an exact integer scale to avoid sub-pixel blurring
            int scale = Math.max(1, tSize / totalModules);
            int finalPixelSize = scale * totalModules;

            int[] pixels = new int[finalPixelSize * finalPixelSize];
            for (int py = 0; py < finalPixelSize; py++) {
                for (int px = 0; px < finalPixelSize; px++) {
                    int moduleY = (py / scale) - margin;
                    int moduleX = (px / scale) - margin;

                    boolean isBlack = false;
                    if (moduleY >= 0 && moduleY < matrixSize && moduleX >= 0 && moduleX < matrixSize) {
                        if (matrix[moduleY][moduleX] == 1) {
                            isBlack = true;
                        }
                    }
                    pixels[py * finalPixelSize + px] = isBlack ? Color.BLACK : Color.WHITE;
                }
            }

            // Create Bitmap directly from pixel array (Maximum sharpness)
            Bitmap bmp = Bitmap.createBitmap(finalPixelSize, finalPixelSize, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, finalPixelSize, 0, 0, finalPixelSize, finalPixelSize);

            return bmp;
        }
    }
}