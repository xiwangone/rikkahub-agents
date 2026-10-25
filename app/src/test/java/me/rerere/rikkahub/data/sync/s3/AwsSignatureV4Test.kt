package me.rerere.rikkahub.data.sync.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class AwsSignatureV4Test {
    // AWS 文档 "Authenticating Requests: Using Query Parameters" 里的示例凭据和时间
    private val awsExample = S3Config(
        endpoint = "https://s3.amazonaws.com",
        accessKeyId = "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        bucket = "examplebucket",
        region = "us-east-1",
        pathStyle = false,
    )
    private val awsExampleTime = ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `presigned url matches the AWS documentation example`() {
        val url = AwsSignatureV4.presignGetUrl(
            config = awsExample,
            path = "/test.txt",
            expires = 86400.seconds,
            now = awsExampleTime,
        )

        assertEquals(
            "https://examplebucket.s3.amazonaws.com/test.txt" +
                "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
                "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request" +
                "&X-Amz-Date=20130524T000000Z" +
                "&X-Amz-Expires=86400" +
                "&X-Amz-SignedHeaders=host" +
                "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
            url
        )
    }

    @Test
    fun `presigned url keeps the bucket in the path for path style and encodes the key`() {
        val url = AwsSignatureV4.presignGetUrl(
            config = awsExample.copy(endpoint = "https://s3.example.com:9000/", pathStyle = true),
            path = "/rikkahub_media/参考 视频.mp4",
            expires = 1.hours,
            now = awsExampleTime,
        )

        assertTrue(
            url,
            url.startsWith(
                "https://s3.example.com:9000/examplebucket/rikkahub_media/" +
                    "%E5%8F%82%E8%80%83%20%E8%A7%86%E9%A2%91.mp4?X-Amz-Algorithm="
            )
        )
        assertTrue(url, url.contains("&X-Amz-Expires=3600&"))
    }

    @Test
    fun `presigned url signature depends on the expiry`() {
        fun signature(seconds: Int) = AwsSignatureV4.presignGetUrl(
            config = awsExample,
            path = "/test.txt",
            expires = seconds.seconds,
            now = awsExampleTime,
        ).substringAfter("X-Amz-Signature=")

        assertTrue(signature(60) != signature(61))
    }

    @Test
    fun `presigned URL resolves a vault secret exactly like a stored literal`() {
        val literal = S3Config(
            endpoint = "https://storage.example.invalid",
            accessKeyId = "SYNTHETIC_ACCESS_KEY",
            secretAccessKey = "synthetic-s3-secret",
            bucket = "synthetic-bucket",
            region = "us-test-1",
            pathStyle = true,
        )
        val reference = "\$\$SYNTHETIC_S3_SECRET"
        val withReference = literal.copy(secretAccessKey = reference)
        val expected = AwsSignatureV4.presignGetUrl(
            config = literal,
            path = "/synthetic/object.txt",
            expires = 60.seconds,
            now = awsExampleTime,
        )
        val actual = AwsSignatureV4.presignGetUrl(
            config = withReference,
            path = "/synthetic/object.txt",
            expires = 60.seconds,
            now = awsExampleTime,
            resolveSecret = { raw -> if (raw == reference) literal.secretAccessKey else raw },
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `presign rejects expiry outside the SigV4 range`() {
        assertThrows(IllegalArgumentException::class.java) {
            AwsSignatureV4.presignGetUrl(awsExample, "/test.txt", 0.seconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AwsSignatureV4.presignGetUrl(awsExample, "/test.txt", 7.days + 1.seconds)
        }
        AwsSignatureV4.presignGetUrl(awsExample, "/test.txt", 7.days)
    }

    @Test
    fun `header signing targets the right host and url for both addressing styles`() {
        val virtualHosted = AwsSignatureV4.sign(awsExample, method = "GET", path = "/test.txt")
        assertEquals("examplebucket.s3.amazonaws.com", virtualHosted.headers["host"])
        assertEquals("https://examplebucket.s3.amazonaws.com/test.txt", virtualHosted.url)

        val pathStyle = AwsSignatureV4.sign(awsExample.copy(pathStyle = true), method = "GET", path = "/test.txt")
        assertEquals("s3.amazonaws.com", pathStyle.headers["host"])
        assertEquals("https://s3.amazonaws.com/examplebucket/test.txt", pathStyle.url)

        // 端点已经带桶名时不再重复拼接
        val bucketInEndpoint = AwsSignatureV4.sign(
            awsExample.copy(endpoint = "https://examplebucket.s3.amazonaws.com"),
            method = "GET",
            path = "/test.txt",
        )
        assertEquals("examplebucket.s3.amazonaws.com", bucketInEndpoint.headers["host"])
    }
}
