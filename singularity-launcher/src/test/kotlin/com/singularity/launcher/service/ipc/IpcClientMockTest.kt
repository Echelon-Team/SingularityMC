package com.singularity.launcher.service.ipc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class IpcClientMockTest {

    // Initial state tests — bez generator coroutine, no hanging risk
    @Test
    fun `initial isConnected false`() = runTest {
        val client = IpcClientMock(scope = TestScope())
        assertFalse(client.isConnected.first())
    }

    @Test
    fun `initial metrics null`() = runTest {
        val client = IpcClientMock(scope = TestScope())
        assertNull(client.metrics.first())
    }

    // Generator tests — używam runTest's own TestScope (this) i disconnect() przed końcem,
    // żeby coroutines nie wisiały po teście.
    @Test
    fun `connect sets isConnected true and starts emitting metrics`() = runTest {
        val client = IpcClientMock(scope = this)
        client.connect("instance-1")
        testScheduler.advanceTimeBy(300)  // >= 1 sample (250ms interval)

        assertTrue(client.isConnected.first())
        val m = client.metrics.first()
        assertNotNull(m)

        client.disconnect()  // CRITICAL: cancel generator przed test end
    }

    @Test
    fun `emitted metrics have TPS near 20`() = runTest {
        val client = IpcClientMock(scope = this)
        client.connect("instance-1")
        testScheduler.advanceTimeBy(300)

        val m = client.metrics.first()
        assertNotNull(m)
        // TPS = 19.5 + sin(t*0.1) * 0.5 ∈ [19.0, 20.0]
        assertTrue(m!!.tps in 18.9..20.1, "TPS: ${m.tps}")

        client.disconnect()
    }

    @Test
    fun `emitted metrics have FPS in 55 to 65 range`() = runTest {
        val client = IpcClientMock(scope = this)
        client.connect("instance-1")
        testScheduler.advanceTimeBy(300)

        val m = client.metrics.first()
        assertNotNull(m)
        assertTrue(m!!.fps in 55.0..65.0, "FPS: ${m.fps}")

        client.disconnect()
    }

    @Test
    fun `disconnect sets isConnected false`() = runTest {
        val client = IpcClientMock(scope = this)
        client.connect("instance-1")
        testScheduler.advanceTimeBy(300)
        client.disconnect()

        assertFalse(client.isConnected.first())
        assertNull(client.metrics.first())  // metrics cleared po disconnect
    }
}
