package ru.blockscan.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.blockscan.app.domain.UpdateLogic

class UpdateLogicTest {
    private val sha = "a".repeat(64)
    private val base = "https://github.com/hackdiaz-dev/blockscan/releases/download/v1.4.0/"

    private fun release(digest: String? = "sha256:$sha"): String {
        val d = if (digest == null) "null" else "\"$digest\""
        return """{"tag_name":"v1.4.0","name":"BlockScan 1.4.0","body":"Новое","html_url":"https://github.com/hackdiaz-dev/blockscan/releases/tag/v1.4.0","draft":false,"prerelease":false,"assets":[{"name":"BlockScan-v1.4.0.apk","browser_download_url":"${base}BlockScan-v1.4.0.apk","size":7100000,"digest":$d},{"name":"BlockScan-v1.4.0.apk.sha256","browser_download_url":"${base}BlockScan-v1.4.0.apk.sha256","size":87}]}"""
    }

    @Test fun parsesReleaseWithDigest() {
        val r = UpdateLogic.parseRelease(release())
        assertNotNull(r)
        assertEquals("1.4.0", r!!.version)
        assertEquals("BlockScan-v1.4.0.apk", r.apkName)
        assertEquals(sha, r.digest)
        assertNotNull(r.sha256Url)
    }

    @Test fun parsesReleaseWithoutDigest() {
        val r = UpdateLogic.parseRelease(release(digest = null))
        assertNotNull(r)
        assertNull(r!!.digest)
        assertTrue(r.sha256Url!!.endsWith(".apk.sha256"))
    }

    @Test fun ignoresDraftsAndPrereleases() {
        assertNull(UpdateLogic.parseRelease(release().replace("\"draft\":false", "\"draft\":true")))
        assertNull(UpdateLogic.parseRelease(release().replace("\"prerelease\":false", "\"prerelease\":true")))
    }

    @Test fun rejectsUntrustedDownloadHost() {
        assertNull(UpdateLogic.parseRelease(release().replace("https://github.com/hackdiaz-dev/blockscan/releases/download", "https://evil.example/x")))
    }

    @Test fun rejectsBrokenJson() {
        assertNull(UpdateLogic.parseRelease("not json"))
    }

    @Test fun comparesVersions() {
        assertTrue(UpdateLogic.isNewer("1.3.0", "1.3.1"))
        assertFalse(UpdateLogic.isNewer("1.3.1", "1.3.1"))
        assertTrue(UpdateLogic.isNewer("1.9.9", "1.10.0"))
        assertFalse(UpdateLogic.isNewer("1.3.1", "v1.3.0"))
        assertTrue(UpdateLogic.isNewer("1.3", "1.3.1"))
        assertFalse(UpdateLogic.isNewer("1.3.1-debug", "1.3.1"))
    }

    @Test fun parsesSha256File() {
        assertEquals(sha, UpdateLogic.parseSha256("${sha.uppercase()}  BlockScan-v1.4.0.apk\n"))
        assertNull(UpdateLogic.parseSha256("нет суммы"))
    }

    @Test fun trustsOnlyGithubHttps() {
        assertTrue(UpdateLogic.isTrustedUrl("https://github.com/a/b"))
        assertTrue(UpdateLogic.isTrustedUrl("https://objects.githubusercontent.com/x"))
        assertFalse(UpdateLogic.isTrustedUrl("http://github.com/a/b"))
        assertFalse(UpdateLogic.isTrustedUrl("https://github.com.evil.example/a"))
        assertFalse(UpdateLogic.isTrustedUrl("https://example.com/a"))
    }
}
