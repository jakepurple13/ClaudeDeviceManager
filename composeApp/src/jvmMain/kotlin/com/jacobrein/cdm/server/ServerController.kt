package com.jacobrein.cdm.server

import com.jacobrein.cdm.service.DeviceManager
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Restarting the HTTP server, as Settings needs it. */
interface ServerControl {
    /** Stops the running server (if any) and starts one on [port]; failures land in [ServerStatus]. */
    suspend fun restart(port: Int)
}

/** Told after every successful bind (startup, Retry, port change); the install lifecycle listens. */
fun interface ServerListener {
    suspend fun onListening(port: Int)
}

/**
 * Owns the one [CdmServer]: Main starts it, Settings restarts it on a new port. Every start rewrites the port
 * file (CdmServer does that); a failed start leaves no port file and puts the reason in [ServerStatus].
 */
class ServerController(
    private val manager: DeviceManager,
    private val status: ServerStatus,
    private val portFile: Path = defaultPortFile(),
    private val listener: ServerListener = ServerListener {},
) : ServerControl {
    private val mutex = Mutex()

    @Volatile private var server: CdmServer? = null

    /** Returns true when the server is listening on [port]. */
    suspend fun start(port: Int): Boolean = mutex.withLock { startLocked(port).also { if (it) notifyLocked(port) } }

    override suspend fun restart(port: Int) {
        mutex.withLock {
            stopLocked()
            if (startLocked(port)) notifyLocked(port)
        }
    }

    /** Under the mutex, so installs happen in the order of the binds. A listener failure never fails the start. */
    private suspend fun notifyLocked(port: Int) {
        try {
            listener.onListening(port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            System.err.println("cdm: after start on port $port: $e")
        }
    }

    /** Idempotent and non-suspending, for the shutdown hook and window close. */
    fun stop() = stopLocked()

    private fun stopLocked() {
        server?.stop()
        server = null
    }

    private suspend fun startLocked(port: Int): Boolean {
        val s = CdmServer(manager, port, portFile)
        return try {
            // Probe first: Ktor reports a taken port from an internal coroutine as an uncaught exception as well as
            // to us, so catch the common case before Ktor sees it.
            withContext(Dispatchers.IO) { probe(port) }
            s.start()
            server = s
            status.clear()
            true
        } catch (e: Throwable) {
            // Ktor reports a bind failure as a CancellationException caused by BindException; only a cancellation
            // of this caller is a real one.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            System.err.println("cdm: server failed to start on port $port: $e")
            runCatching { s.stop() } // engine only: it never wrote the port file, so it leaves it alone
            status.fail(failureMessage(port, e))
            false
        }
    }

    private fun probe(port: Int) {
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        }
    }

    private fun failureMessage(port: Int, e: Throwable): String {
        val bind = generateSequence(e) { it.cause }.any { it is BindException }
        return if (bind) "Port $port is in use — change the port below."
        else "Could not listen on 127.0.0.1:$port: ${e.message ?: e}"
    }
}
