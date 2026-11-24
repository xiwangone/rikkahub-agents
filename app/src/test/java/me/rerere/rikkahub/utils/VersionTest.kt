package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun `basic version comparison`() {
        assertTrue(Version("1.0.0") < Version("2.0.0"))
        assertTrue(Version("1.0.0") < Version("1.1.0"))
        assertTrue(Version("1.0.0") < Version("1.0.1"))
        assertEquals(0, Version("1.0.0").compareTo(Version("1.0.0")))
    }

    @Test
    fun `different length versions`() {
        assertEquals(0, Version("1.0").compareTo(Version("1.0.0")))
        assertTrue(Version("1.0") < Version("1.0.1"))
    }

    @Test
    fun `prerelease has lower precedence than release`() {
        assertTrue(Version("1.0.0-alpha") < Version("1.0.0"))
        assertTrue(Version("1.0.0-beta") < Version("1.0.0"))
        assertTrue(Version("1.0.0-rc.1") < Version("1.0.0"))
    }

    @Test
    fun `prerelease ordering`() {
        assertTrue(Version("1.0.0-alpha") < Version("1.0.0-beta"))
        assertTrue(Version("1.0.0-beta") < Version("1.0.0-rc"))
        assertTrue(Version("1.0.0-alpha.1") < Version("1.0.0-alpha.2"))
    }

    @Test
    fun `prerelease with more fields has higher precedence`() {
        assertTrue(Version("1.0.0-alpha") < Version("1.0.0-alpha.1"))
    }

    @Test
    fun `numeric prerelease identifiers compared as numbers`() {
        assertTrue(Version("1.0.0-alpha.2") < Version("1.0.0-alpha.10"))
    }

    @Test
    fun `numeric identifiers have lower precedence than string`() {
        assertTrue(Version("1.0.0-1") < Version("1.0.0-alpha"))
    }

    @Test
    fun `build metadata is ignored`() {
        assertEquals(0, Version("1.0.0+build1").compareTo(Version("1.0.0+build2")))
        assertEquals(0, Version("1.0.0-alpha+build").compareTo(Version("1.0.0-alpha")))
    }

    @Test
    fun `semver full precedence chain`() {
        // From SemVer spec: 1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-beta < 1.0.0-beta.2 < 1.0.0-rc.1 < 1.0.0
        val versions = listOf(
            Version("1.0.0-alpha"),
            Version("1.0.0-alpha.1"),
            Version("1.0.0-beta"),
            Version("1.0.0-beta.2"),
            Version("1.0.0-rc.1"),
            Version("1.0.0"),
        )
        for (i in 0 until versions.size - 1) {
            assertTrue(
                "${versions[i].value} should be < ${versions[i + 1].value}",
                versions[i] < versions[i + 1]
            )
        }
    }

    @Test
    fun `companion compare function`() {
        assertTrue(Version.compare("1.0.0", "2.0.0") < 0)
        assertTrue(Version.compare("2.0.0", "1.0.0") > 0)
        assertEquals(0, Version.compare("1.0.0", "1.0.0"))
    }

    @Test
    fun `string extension operators`() {
        assertTrue("1.0.0" < Version("2.0.0"))
        assertTrue(Version("2.0.0") > "1.0.0")
    }

    @Test
    fun `commit hash fingerprint is ignored in comparison`() {
        // 出包时拼进 versionName 的 commit 短哈希是构建标识，不是预发布版本
        assertEquals(0, Version("3.0.7-297bd64b5").compareTo(Version("3.0.7")))
        assertEquals(0, Version("3.0.7").compareTo(Version("3.0.7-297bd64b5")))
    }

    @Test
    fun `fingerprint build is still older than next release`() {
        assertTrue(Version("3.0.7-297bd64b5") < Version("3.0.8"))
    }

    @Test
    fun `fingerprint build is newer than prerelease of same version`() {
        assertTrue(Version("3.0.7-297bd64b5") > Version("3.0.7-rc1"))
        assertTrue(Version("3.0.7-297bd64b5") > Version("3.0.7-alpha"))
    }

    @Test
    fun `real prerelease identifiers are unaffected by fingerprint rule`() {
        assertTrue(Version("3.0.7-alpha") < Version("3.0.7"))
        assertTrue(Version("3.0.7-beta") < Version("3.0.7"))
        assertTrue(Version("3.0.7-rc.1") < Version("3.0.7"))
    }

    @Test
    fun `full length commit hash fingerprint is ignored`() {
        assertEquals(
            0,
            Version("3.0.7-297bd64b568affad4d1b6a8c11904a5d3d66bf63").compareTo(Version("3.0.7"))
        )
    }

    @Test
    fun `trailing fingerprint segment after a prerelease is ignored`() {
        assertEquals(0, Version("3.0.7-beta.297bd64b5").compareTo(Version("3.0.7-beta")))
        assertTrue(Version("3.0.7-beta.297bd64b5") < Version("3.0.7"))
    }

    @Test
    fun `short hex-like prerelease below 7 chars is kept`() {
        // 6 位以下不算指纹，仍按预发布比较
        assertTrue(Version("3.0.7-abcdef") < Version("3.0.7"))
    }
}
