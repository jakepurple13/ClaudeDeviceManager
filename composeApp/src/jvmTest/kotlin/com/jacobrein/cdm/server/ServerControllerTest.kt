package com.jacobrein.cdm.server

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ServerControllerTest {
    private val portFile = Files.createTempDirectory("cdm-port").resolve("port")
    private val status = ServerStatus()
    private val controller = ServerController(FakeManager(), status, portFile)
    private val http = HttpClient.newHttpClient()

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun pings(port: Int) = runCatching {
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/cdm/ping")).build(), HttpResponse.BodyHandlers.ofString()).body()
    }.getOrNull() == "cdm-ok\n"

    @Test
    fun restartMovesServerAndPortFile() = runBlocking {
        val a = freePort()
        try {
            assertTrue(controller.start(a))
            // Picked after a is bound, so it can't be the same port.
            val b = freePort()
            assertEquals("$a", portFile.readText())
            controller.restart(b)
            assertTrue(pings(b))
            assertFalse(pings(a))
            assertEquals("$b", portFile.readText())
            assertNull(status.serverError.value)
        } finally {
            controller.stop()
        }
        assertFalse(portFile.exists())
    }

    @Test
    fun failedStartLeavesAnotherInstancesPortFileAlone() = runBlocking {
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
            // Another instance is up on this port and wrote the file.
            Files.createDirectories(portFile.parent)
            Files.writeString(portFile, "${taken.localPort}")
            assertFalse(controller.start(taken.localPort))
            assertEquals("${taken.localPort}", portFile.readText())
            controller.stop()
            assertEquals("${taken.localPort}", portFile.readText())
        }
    }

    @Test
    fun portInUseReportedThenClearedByGoodRestart() = runBlocking {
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
            try {
                assertFalse(controller.start(taken.localPort))
                val err = assertNotNull(status.serverError.value)
                assertTrue("in use" in err, err)
                controller.restart(freePort())
                assertNull(status.serverError.value)
            } finally {
                controller.stop()
            }
        }
    }
}
