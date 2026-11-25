package com.litebrowser.app.m3u8

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128 加密 HLS 的本地解密。
 *
 * 下载器常把分片存成无扩展名的 0/1/2，同时保留 #EXT-X-KEY。
 * FFmpeg 的 HLS demuxer 因扩展名问题读不了，所以先把分片解密成临时 .ts，
 * 再交给 concat demuxer 拼接。密钥和 IV 都按 HLS 规范解析。
 *
 * 关键：播放列表可以在中途换 #EXT-X-KEY（key rotation），
 * 每个分片必须用它当时生效的 key/IV 解，否则从换 key 处开始 BAD_DECRYPT。
 */
object HlsDecryptor {

    data class Result(
        val paths: List<String>,
        val error: String? = null,
        /** 解密失败被跳过的分片序号（1 起算） */
        val skipped: List<Int> = emptyList(),
        /** 单个分片的失败原因，供汇总展示 */
        val failures: List<String> = emptyList()
    )

    fun decrypt(context: Context, info: SegmentResolver.PlaylistInfo, workDir: File): Result {
        if (info.segments.isEmpty()) return Result(emptyList(), "播放列表里没有本地分片")
        if (info.unsupportedKey) {
            return Result(emptyList(), "播放列表使用了 SAMPLE-AES / DRM 加密，本版本只支持 AES-128")
        }

        try {
            workDir.mkdirs()
        } catch (e: Exception) {
            return Result(emptyList(), "创建临时目录失败：${e.message}")
        }

        // 同一把 key 常被多段共用，缓存避免重复读文件/联网
        val keyCache = HashMap<String, ByteArray>()
        val keyErrors = HashMap<String, String>()
        val output = ArrayList<String>(info.segments.size)
        val skipped = ArrayList<Int>()
        val failures = ArrayList<String>()

        for ((index, segment) in info.segments.withIndex()) {
            val keySpec = info.segmentKeys.getOrNull(index)
            if (keySpec == null || keySpec.keyUri.isNullOrBlank()) {
                // METHOD=NONE 或无 KEY：这一段本来就是明文，直接用原文件
                output.add(segment)
                continue
            }
            val uri = keySpec.keyUri!!
            // 这把 key 上次已经读失败，本段同样跳过（不能在 inline lambda 里 continue）
            val knownError = keyErrors[uri]
            if (knownError != null) {
                failures.add("第 ${index + 1} 段：$uri $knownError")
                skipped.add(index + 1)
                continue
            }
            val key: ByteArray
            val cached = keyCache[uri]
            if (cached != null) {
                key = cached
            } else {
                try {
                    key = loadKey(uri)
                    keyCache[uri] = key
                } catch (e: Exception) {
                    val message = "读取密钥失败：${e.message}"
                    keyErrors[uri] = message
                    failures.add("第 ${index + 1} 段：$uri $message")
                    skipped.add(index + 1)
                    continue
                }
            }
            if (key.size != 16) {
                val message = "密钥长度不是 16 字节（实际 ${key.size}）"
                keyErrors[uri] = message
                failures.add("第 ${index + 1} 段：$message")
                skipped.add(index + 1)
                continue
            }

            val iv: ByteArray
            if (keySpec.iv == null) {
                iv = sequenceIv(keySpec.sequence)
            } else {
                val parsed = parseHex(keySpec.iv!!)
                if (parsed == null) {
                    failures.add("第 ${index + 1} 段：IV 格式无法识别：${keySpec.iv}")
                    skipped.add(index + 1)
                    continue
                }
                iv = parsed
            }

            val target = File(workDir, String.format("seg_%06d.ts", index))
            val error = decryptOne(segment, target.absolutePath, key, iv)
            if (error == null) {
                output.add(target.absolutePath)
            } else {
                try {
                    target.delete()
                } catch (e: Exception) {
                }
                failures.add("第 ${index + 1} 段：$error")
                skipped.add(index + 1)
            }
        }

        if (output.isEmpty()) {
            return Result(
                emptyList(),
                if (failures.isEmpty()) "没有可用的分片" else failures.joinToString("；"),
                skipped,
                failures
            )
        }
        // 有部分成功：error 留空，让调用方继续拼接成功段；skipped/failures 供汇总提示
        return Result(output, null, skipped, failures)
    }

    /** 只解密第一个分片，供缩略图抽帧使用，避免为一张图解完整部视频 */
    fun decryptFirst(context: Context, info: SegmentResolver.PlaylistInfo, workDir: File): Result {
        val first = info.segments.firstOrNull()
            ?: return Result(emptyList(), "播放列表里没有本地分片")
        if (info.unsupportedKey) {
            return Result(emptyList(), "播放列表使用了 SAMPLE-AES / DRM 加密，无法预览")
        }
        val keySpec = info.segmentKeys.firstOrNull()
        if (keySpec == null || keySpec.keyUri.isNullOrBlank()) {
            // 明文分片，直接交给调用方
            return Result(listOf(first))
        }
        val key = try {
            loadKey(keySpec.keyUri!!)
        } catch (e: Exception) {
            return Result(emptyList(), "读取密钥失败：${e.message}")
        }
        if (key.size != 16) {
            return Result(emptyList(), "密钥长度不是 16 字节（实际 ${key.size}）")
        }
        val iv = if (keySpec.iv == null) {
            sequenceIv(keySpec.sequence)
        } else {
            parseHex(keySpec.iv!!)
                ?: return Result(emptyList(), "IV 格式无法识别：${keySpec.iv}")
        }
        return try {
            workDir.mkdirs()
            val target = File(workDir, "first.ts")
            val error = decryptOne(first, target.absolutePath, key, iv)
            if (error != null) Result(emptyList(), error) else Result(listOf(target.absolutePath))
        } catch (e: Exception) {
            Result(emptyList(), "创建临时目录失败：${e.message}")
        }
    }

    fun cleanup(paths: Collection<String>, workDir: File) {
        paths.forEach { try { File(it).delete() } catch (e: Exception) {} }
        try {
            workDir.delete()
        } catch (e: Exception) {
            // 临时目录删不掉下次会被覆盖，不影响结果
        }
    }

    /**
     * 只清理 workDir 里我们生成的临时文件。
     * 明文段（METHOD=NONE）用的是原始分片路径，绝不能跟着删掉。
     */
    fun cleanup(result: Result, workDir: File) {
        val workPath = workDir.absolutePath
        cleanup(
            result.paths.filter { path ->
                try {
                    File(path).parentFile?.absolutePath == workPath
                } catch (e: Exception) {
                    false
                }
            },
            workDir
        )
    }

    /** 解单个分片；成功返回 null，失败返回原因 */
    private fun decryptOne(inputPath: String, outputPath: String, key: ByteArray, iv: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            FileInputStream(inputPath).use { fileInput ->
                CipherInputStream(fileInput, cipher).use { decrypted ->
                    FileOutputStream(outputPath).use { fileOutput ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val read = decrypted.read(buffer)
                            if (read <= 0) break
                            fileOutput.write(buffer, 0, read)
                        }
                        fileOutput.flush()
                    }
                }
            }
            null
        } catch (e: Exception) {
            "解密失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun loadKey(uri: String): ByteArray {
        if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) {
            val connection = URL(uri).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.inputStream.use { return it.readBytes() }
        }
        return File(uri).readBytes()
    }

    private fun parseHex(text: String): ByteArray? {
        val hex = text.removePrefix("0x").removePrefix("0X").replace(" ", "")
        if (hex.length != 32) return null
        val result = ByteArray(16)
        for (i in 0 until 16) {
            val value = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            result[i] = value.toByte()
        }
        return result
    }

    /** HLS 规范：未指定 IV 时用分片的 media sequence 作为 128 位大端整数 */
    private fun sequenceIv(sequence: Long): ByteArray {
        val result = ByteArray(16)
        result[12] = ((sequence shr 24) and 0xFF).toByte()
        result[13] = ((sequence shr 16) and 0xFF).toByte()
        result[14] = ((sequence shr 8) and 0xFF).toByte()
        result[15] = (sequence and 0xFF).toByte()
        return result
    }
}
