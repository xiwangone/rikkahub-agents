package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCryptoTest {

    private val testIter = 2_000

    @Test
    fun `encrypt and decrypt empty and multi-chunk payloads roundtrip cleanly`() {
        val dir = Files.createTempDirectory("backup_crypto_test").toFile()
        try {
            val sizes = listOf(0, 1, 1024, 4096, 4097, 12_345, 32 * 1024 + 7)
            val random = SecureRandom()
            for (size in sizes) {
                val plainBytes = ByteArray(size).also { random.nextBytes(it) }
                val plainFile = File(dir, "plain_$size.bin").apply { writeBytes(plainBytes) }
                val encFile = File(dir, "enc_$size.bin")
                val decFile = File(dir, "dec_$size.bin")

                BackupCrypto.encryptFile(
                    plainFile = plainFile,
                    encFile = encFile,
                    password = "correct-horse-battery-staple",
                    iterations = testIter,
                    chunkSize = 4096,
                )
                assertTrue(BackupCrypto.isEncrypted(encFile))
                assertFalse(BackupCrypto.isEncrypted(plainFile))

                BackupCrypto.decryptFile(encFile, decFile, "correct-horse-battery-staple")
                assertArrayEquals("Size $size roundtrip mismatch", plainBytes, decFile.readBytes())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `wrong password or tampered ciphertext or truncated chunk throws IllegalArgumentException`() {
        val dir = Files.createTempDirectory("backup_crypto_tamper").toFile()
        try {
            val plainBytes = ByteArray(10_000) { (it * 31).toByte() }
            val plainFile = File(dir, "plain.bin").apply { writeBytes(plainBytes) }
            val encFile = File(dir, "enc.bin")
            val decFile = File(dir, "dec.bin")

            BackupCrypto.encryptFile(
                plainFile = plainFile,
                encFile = encFile,
                password = "secret-password",
                iterations = testIter,
                chunkSize = 4096,
            )

            // 1. Wrong password
            try {
                BackupCrypto.decryptFile(encFile, decFile, "wrong-password")
                fail("Expected IllegalArgumentException for wrong password")
            } catch (_: IllegalArgumentException) {
                assertFalse("Failed decrypt must clean up partial output", decFile.exists())
            }

            // 2. Tamper 1 byte in ciphertext
            val tamperedFile = File(dir, "tampered.bin")
            val encBytes = encFile.readBytes()
            encBytes[encBytes.size - 10] = (encBytes[encBytes.size - 10].toInt() xor 0x55).toByte()
            tamperedFile.writeBytes(encBytes)
            try {
                BackupCrypto.decryptFile(tamperedFile, decFile, "secret-password")
                fail("Expected IllegalArgumentException for tampered ciphertext")
            } catch (_: IllegalArgumentException) {
                assertFalse(decFile.exists())
            }

            // 3. Truncate last chunk (simulate incomplete file / dropped final chunk)
            val truncatedFile = File(dir, "truncated.bin")
            // Header is 37 bytes; first chunk is 4(len)+1(flag)+4096+16 = 4117 bytes -> 4154 bytes
            truncatedFile.writeBytes(encFile.readBytes().copyOf(37 + 4117))
            try {
                BackupCrypto.decryptFile(truncatedFile, decFile, "secret-password")
                fail("Expected IllegalArgumentException for truncated multi-chunk stream")
            } catch (_: IllegalArgumentException) {
                assertFalse(decFile.exists())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `decryptFile maintains backward compatibility with legacy V1 format`() {
        val dir = Files.createTempDirectory("backup_crypto_v1").toFile()
        try {
            val plainBytes = "legacy-v1-encrypted-backup-payload".repeat(100).toByteArray()
            val v1EncFile = File(dir, "v1.enc")
            val decFile = File(dir, "v1.dec")
            val password = "legacy-password"
            val salt = ByteArray(16) { (it + 1).toByte() }
            val iv = ByteArray(12) { (it + 17).toByte() }

            val spec = PBEKeySpec(password.toCharArray(), salt, testIter, 256)
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))

            FileOutputStream(v1EncFile).use { out ->
                out.write("RHBE".toByteArray(Charsets.US_ASCII))
                out.write(1) // VERSION_V1 = 1
                out.write(
                    byteArrayOf(
                        (testIter ushr 24).toByte(),
                        (testIter ushr 16).toByte(),
                        (testIter ushr 8).toByte(),
                        testIter.toByte(),
                    ),
                )
                out.write(salt)
                out.write(iv)
                CipherOutputStream(out, cipher).use { cos ->
                    cos.write(plainBytes)
                }
            }

            BackupCrypto.decryptFile(v1EncFile, decFile, password)
            assertEquals(plainBytes.size, decFile.length().toInt())
            assertArrayEquals(plainBytes, decFile.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

}
