package eu.kanade.tachiyomi.data.historysync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GoogleDriveAuthorizationTest {
    private val account = HistorySyncCredentials(
        provider = HistorySyncProvider.GOOGLE_DRIVE,
        googleProjectNumber = "123456",
        googlePermissionId = "987654",
        googleEmail = "reader@example.com",
    )

    @Test
    fun `previous WebDAV credentials decode without a provider migration`() {
        val json = "{\"root\":\"https://example.com/\",\"username\":\"reader\",\"password\":\"secret\"}"
        val saved = HistorySyncProtocol.json.decodeFromString<HistorySyncCredentials>(json)
        assertEquals(HistorySyncProvider.WEBDAV, saved.provider)
        assertEquals("secret", saved.password)
    }

    @Test
    fun `silent token must verify permission ID before use and is cached within one sync`() = runTest {
        var authorizations = 0
        var verifications = 0
        val tokens = GoogleDriveSessionTokens(
            account,
            authorize = {
                authorizations++
                "token"
            },
            clear = { error("Unexpected invalidation") },
            identity = {
                verifications++
                assertEquals("token", it.accessToken())
                GoogleDriveAccount(account.googlePermissionId, "renamed@example.com")
            },
        )
        assertEquals("token", tokens.accessToken())
        assertEquals("token", tokens.accessToken())
        assertEquals(1, authorizations)
        assertEquals(1, verifications)
    }

    @Test
    fun `invalidating an expired token reacquires and rechecks account identity`() = runTest {
        var authorizations = 0
        var verifications = 0
        val cleared = mutableListOf<String>()
        val tokens = GoogleDriveSessionTokens(
            account,
            authorize = { "token-${++authorizations}" },
            clear = { cleared += it },
            identity = {
                verifications++
                GoogleDriveAccount(account.googlePermissionId, account.googleEmail)
            },
        )
        assertEquals("token-1", tokens.accessToken())
        tokens.invalidate("token-1")
        assertEquals("token-2", tokens.accessToken())
        assertEquals(listOf("token-1"), cleared)
        assertEquals(2, verifications)
    }

    @Test
    fun `resolution required in silent authorization never reaches Drive or clears an account`() = runTest {
        var identities = 0
        var clears = 0
        val tokens = GoogleDriveSessionTokens(
            account,
            authorize = { throw GoogleDriveReconnectRequired() },
            clear = { clears++ },
            identity = {
                identities++
                GoogleDriveAccount(account.googlePermissionId, account.googleEmail)
            },
        )
        assertThrows(GoogleDriveReconnectRequired::class.java) { runBlocking { tokens.accessToken() } }
        assertEquals(0, identities)
        assertEquals(0, clears)
    }

    @Test
    fun `cancelled authorization propagates without reading identity or clearing a grant`() = runTest {
        var clears = 0
        var identities = 0
        val tokens = GoogleDriveSessionTokens(
            account,
            authorize = { throw CancellationException("cancelled") },
            clear = { clears++ },
            identity = {
                identities++
                error("Cancelled authorization cannot verify or save an identity")
            },
        )
        assertThrows(CancellationException::class.java) { runBlocking { tokens.accessToken() } }
        assertEquals(0, identities)
        assertEquals(0, clears)
    }

    @Test
    fun `same email with a different immutable permission ID never supplies a token`() = runTest {
        val tokens = GoogleDriveSessionTokens(
            account,
            authorize = { "other-account-token" },
            clear = {},
            identity = { GoogleDriveAccount("111111", account.googleEmail) },
        )
        assertThrows(GoogleDriveReconnectRequired::class.java) { runBlocking { tokens.accessToken() } }
    }

    @Test
    fun `expired identity lookup token can renew once before identity is accepted`() = runTest {
        var authorizations = 0
        val cleared = mutableListOf<String>()
        val tokens = GoogleDriveSessionTokens(
            account,
            authorize = { "token-${++authorizations}" },
            clear = { cleared += it },
            identity = {
                assertEquals("token-1", it.accessToken())
                it.invalidate("token-1")
                assertEquals("token-2", it.accessToken())
                GoogleDriveAccount(account.googlePermissionId, account.googleEmail)
            },
        )
        assertEquals("token-2", tokens.accessToken())
        assertEquals(listOf("token-1"), cleared)
    }
}
