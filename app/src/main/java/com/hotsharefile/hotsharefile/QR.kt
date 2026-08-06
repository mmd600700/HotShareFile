package com.hotsharefile.hotsharefile

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

object QR {

    fun generateQrCode(targetSize: Int, text: String): Bitmap {
        return QrEncoder.encode(targetSize, text)
    }

    private object QrEncoder {

        private val EXP = IntArray(512)
        private val LOG = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if ((x and 0x100) != 0) x = x xor 0x11D
            }
            for (i in 255 until 512) {
                EXP[i] = EXP[i - 255]
            }
        }

        private fun gfMultiply(x: Int, y: Int): Int {
            if (x == 0 || y == 0) return 0
            return EXP[LOG[x] + LOG[y]]
        }

        private fun getGenerator(degree: Int): IntArray {
            var gen = intArrayOf(1)
            for (i in 0 until degree) {
                val nextGen = IntArray(gen.size + 1)
                nextGen[0] = gen[0]
                for (j in 1 until gen.size) {
                    nextGen[j] = gen[j] xor gfMultiply(gen[j - 1], EXP[i])
                }
                nextGen[gen.size] = gfMultiply(gen[gen.size - 1], EXP[i])
                gen = nextGen
            }
            return gen
        }

        private fun calculateEcc(data: IntArray, gen: IntArray): IntArray {
            val msg = IntArray(data.size + gen.size - 1)
            System.arraycopy(data, 0, msg, 0, data.size)
            for (i in data.indices) {
                val factor = msg[i]
                if (factor == 0) continue
                for (j in gen.indices) {
                    msg[i + j] = msg[i + j] xor gfMultiply(gen[j], factor)
                }
            }
            val ecc = IntArray(gen.size - 1)
            System.arraycopy(msg, data.size, ecc, 0, ecc.size)
            return ecc
        }

        private fun drawFinder(matrix: Array<IntArray>, startX: Int, startY: Int) {
            for (y in -1..7) {
                for (x in -1..7) {
                    val cx = startX + x
                    val cy = startY + y
                    if (cx !in 0..32 || cy !in 0..32) continue

                    matrix[cy][cx] = when {
                        x == -1 || x == 7 || y == -1 || y == 7 -> 0
                        x == 0 || x == 6 || y == 0 || y == 6 -> 1
                        x == 1 || x == 5 || y == 1 || y == 5 -> 0
                        else -> 1
                    }
                }
            }
        }

        fun encode(tSize: Int, content: String): Bitmap {
            val rawData = content.toByteArray(StandardCharsets.UTF_8)
            require(rawData.size <= 62) { "Text exceeds Version 4-M capacity (62 bytes)." }

            val bits = BooleanArray(512)
            var bitPos = 0

            // Byte Mode Indicator (0100)
            bits[bitPos++] = false; bits[bitPos++] = true
            bits[bitPos++] = false; bits[bitPos++] = false

            // Length Indicator (8 bits for Version 4)
            val len = rawData.size
            for (i in 7 downTo 0) {
                bits[bitPos++] = ((len shr i) and 1) == 1
            }

            // Data Bits
            for (b in rawData) {
                val bInt = b.toInt()
                for (i in 7 downTo 0) {
                    bits[bitPos++] = ((bInt shr i) and 1) == 1
                }
            }

            // Terminator (Up to 4 zeros)
            val terminatorLimit = min(4, 512 - bitPos)
            for (i in 0 until terminatorLimit) {
                bits[bitPos++] = false
            }

            // Byte Alignment Padding
            while (bitPos % 8 != 0) {
                bits[bitPos++] = false
            }

            // Padding Bytes (0xEC and 0x11)
            var padSwitch = true
            while (bitPos < 512) {
                val padByte = if (padSwitch) 0xEC else 0x11
                for (i in 7 downTo 0) {
                    bits[bitPos++] = ((padByte shr i) and 1) == 1
                }
                padSwitch = !padSwitch
            }

            // Extract Bytes
            val dataWords = IntArray(64)
            for (i in 0 until 64) {
                var b = 0
                for (j in 0 until 8) {
                    if (bits[i * 8 + j]) b = b or (1 shl (7 - j))
                }
                dataWords[i] = b
            }

            // Split into 2 blocks for Version 4-M
            val block1Data = IntArray(32)
            val block2Data = IntArray(32)
            System.arraycopy(dataWords, 0, block1Data, 0, 32)
            System.arraycopy(dataWords, 32, block2Data, 0, 32)

            val gen = getGenerator(18)
            val block1Ecc = calculateEcc(block1Data, gen)
            val block2Ecc = calculateEcc(block2Data, gen)

            // Interleave Data and ECC
            val finalData = IntArray(100)
            var idx = 0
            for (i in 0 until 32) {
                finalData[idx++] = block1Data[i]
                finalData[idx++] = block2Data[i]
            }
            for (i in 0 until 18) {
                finalData[idx++] = block1Ecc[i]
                finalData[idx++] = block2Ecc[i]
            }

            val matrix = Array(33) { IntArray(33) { -1 } }

            // Patterns
            drawFinder(matrix, 0, 0)
            drawFinder(matrix, 26, 0)
            drawFinder(matrix, 0, 26)

            // Alignment Pattern for Version 4 at (26, 26)
            for (y in -2..2) {
                for (x in -2..2) {
                    val isBlack = x == -2 || x == 2 || y == -2 || y == 2 || (x == 0 && y == 0)
                    matrix[26 + y][26 + x] = if (isBlack) 1 else 0
                }
            }

            // Timing Patterns
            for (i in 8 until 25) {
                matrix[6][i] = if (i % 2 == 0) 1 else 0
                matrix[i][6] = if (i % 2 == 0) 1 else 0
            }

            // Dark Module
            matrix[25][8] = 1

            // Reserve Format Info Area
            for (i in 0 until 9) {
                if (matrix[8][i] == -1) matrix[8][i] = 2
                if (matrix[i][8] == -1) matrix[i][8] = 2
            }
            for (i in 25 until 33) {
                if (matrix[8][i] == -1) matrix[8][i] = 2
                if (matrix[i][8] == -1) matrix[i][8] = 2
            }

            // Data Placement (ZigZag)
            var bitIdx = 0
            var dir = -1
            var x = 32
            var y = 32
            while (x > 0) {
                if (x == 6) x--
                while (y in 0 until 33) {
                    for (i in 0 until 2) {
                        val cx = x - i
                        if (matrix[y][cx] == -1) {
                            var bit = false
                            if (bitIdx < 800) {
                                bit = ((finalData[bitIdx / 8] shr (7 - (bitIdx % 8))) and 1) == 1
                            }
                            val maskBit = (y + cx) % 2 == 0
                            matrix[y][cx] = if (bit xor maskBit) 1 else 0
                            bitIdx++
                        }
                    }
                    y += dir
                }
                dir = -dir
                y += dir
                x -= 2
            }

            // Format Info Bits
            val formatBits = intArrayOf(0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1)
            val xs1 = intArrayOf(8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0)
            val ys1 = intArrayOf(0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8)
            val xs2 = intArrayOf(8, 8, 8, 8, 8, 8, 8, 25, 26, 27, 28, 29, 30, 31, 32)
            val ys2 = intArrayOf(32, 31, 30, 29, 28, 27, 26, 8, 8, 8, 8, 8, 8, 8, 8)

            for (i in 0 until 15) {
                matrix[ys1[i]][xs1[i]] = formatBits[i]
                matrix[ys2[i]][xs2[i]] = formatBits[i]
            }

            // Pixel Rendering
            val margin = 4
            val matrixSize = 33
            val totalModules = matrixSize + (2 * margin)

            val scale = max(1, tSize / totalModules)
            val finalPixelSize = scale * totalModules

            val pixels = IntArray(finalPixelSize * finalPixelSize)
            for (py in 0 until finalPixelSize) {
                for (px in 0 until finalPixelSize) {
                    val moduleY = (py / scale) - margin
                    val moduleX = (px / scale) - margin

                    var isBlack = false
                    if (moduleY in 0 until matrixSize && moduleX in 0 until matrixSize) {
                        if (matrix[moduleY][moduleX] == 1) {
                            isBlack = true
                        }
                    }
                    pixels[py * finalPixelSize + px] = if (isBlack) Color.BLACK else Color.WHITE
                }
            }

            return Bitmap.createBitmap(finalPixelSize, finalPixelSize, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, finalPixelSize, 0, 0, finalPixelSize, finalPixelSize)
            }
        }
    }
}