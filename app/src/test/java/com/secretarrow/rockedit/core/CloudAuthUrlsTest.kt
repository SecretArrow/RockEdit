package com.secretarrow.rockedit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-branch tests for [CloudAuthUrls] and provider mapping (v0.15.0). */
class CloudAuthUrlsTest {
    @Test
    fun `drive url carries offline access and consent params`() {
        val url = CloudAuthUrls.build(CloudProvider.GOOGLE_DRIVE, "my-client")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=my-client"))
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1"))
        assertTrue(url.contains("scope="))
    }

    @Test
    fun `dropbox url requests offline token access`() {
        val url = CloudAuthUrls.build(CloudProvider.DROPBOX, "cid")
        assertTrue(url.startsWith("https://www.dropbox.com/oauth2/authorize?"))
        assertTrue(url.contains("token_access_type=offline"))
        assertTrue(url.contains("scope="))
    }

    @Test
    fun `onedrive url uses query response mode and offline access scope`() {
        val url = CloudAuthUrls.build(CloudProvider.ONEDRIVE, "cid")
        assertTrue(url.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"))
        assertTrue(url.contains("response_mode=query"))
        assertTrue(url.contains("offline_access"))
    }

    @Test
    fun `empty client id throws an actionable error`() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudAuthUrls.build(CloudProvider.GOOGLE_DRIVE, "   ")
        }
    }

    @Test
    fun `custom redirect and state are honored`() {
        val url = CloudAuthUrls.build(CloudProvider.DROPBOX, "cid", redirectUri = "http://localhost:8080/x", state = "st-1")
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fx"))
        assertTrue(url.contains("state=st-1"))
    }

    @Test
    fun `provider mapping covers exactly the three cloud types`() {
        assertEquals(CloudProvider.GOOGLE_DRIVE, RemoteType.GOOGLE_DRIVE.cloudProvider())
        assertEquals(CloudProvider.DROPBOX, RemoteType.DROPBOX.cloudProvider())
        assertEquals(CloudProvider.ONEDRIVE, RemoteType.ONEDRIVE.cloudProvider())
        assertEquals(null, RemoteType.FTP.cloudProvider())
        assertEquals(null, RemoteType.SFTP.cloudProvider())
        assertEquals(null, RemoteType.WEBDAV.cloudProvider())
        assertEquals(null, RemoteType.GITHUB.cloudProvider())
        assertEquals(null, RemoteType.GITLAB.cloudProvider())
        assertEquals(null, RemoteType.USB_OTG.cloudProvider())
        assertTrue(RemoteType.GOOGLE_DRIVE.isCloud)
        assertTrue(RemoteType.DROPBOX.isCloud)
        assertTrue(RemoteType.ONEDRIVE.isCloud)
        assertTrue(!RemoteType.FTP.isCloud)
        assertTrue(!RemoteType.USB_OTG.isCloud)
    }

    @Test
    fun `client secret requirement matches provider design`() {
        assertTrue(CloudProvider.DROPBOX.needsClientSecret)
        assertTrue(!CloudProvider.GOOGLE_DRIVE.needsClientSecret)
        assertTrue(!CloudProvider.ONEDRIVE.needsClientSecret)
    }
}
