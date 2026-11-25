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
 */
object HlsDecryptor {

    data class Result(val paths: List<String>, val error: String? = null)

    fun decrypt(context: Context, info: SegmentResolver.PlaylistInfo, workDir: File): Result {
        val keyUri = info.keyUri
            ?: return Result(emptyList(), "播放列表里没有找到密钥地址")
        val key = try {
            loadKey(keyUri)
        } catch (e: Exception) {
            return Result(emptyList(), "读取密钥失败：${e.message}")
        }
        if (key.size != 16) {
            return Result(emptyList(), "密钥长度不是 16 字节（实际 ${key.size}）")
        }

        val explicitIv = if (info.keyIv == null) null else parseHex(info.keyIv!!)
        if (info.keyIv != null && explicitIv == null) {
            return Result(emptyList(), "IV 格式无法识别：${info.keyIv}")
        }

        try {
            workDir.mkdirs()
        } catch (e: Exception) {
            return Result(emptyList(), "创建临时目录失败：${e.message}")
        }

        val output = ArrayList<String>(info.segments.size)
        try {
            for ((index, segment) in info.segments.withIndex()) {
                val iv = explicitIv ?: sequenceIv(info.mediaSequence + index)
                val target = File(workDir, String.format("seg_%06d.ts", index))
                val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    IvParameterSpec(iv)
                )
                FileInputStream(segment).use { fileInput ->
                    CipherInputStream(fileInput, cipher).use { decrypted ->
                        FileOutputStream(target).use { fileOutput ->
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
                output.add(target.absolutePath)
            }
            return Result(output)
        } catch (e: Exception) {
            output.forEach { try { File(it).delete() } catch (ignored: Exception) {} }
            return Result(emptyList(), "第 ${output.size + 1} 个分片解密失败：${e.message}")
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
