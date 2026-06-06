package org.owntracks.android.net.mqtt

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The outbound loop wedged in the field when a HiveMQ publish future was never settled
 * (hivemq-mqtt-client #554/#612). [await] must always resume by force-completing the future, so
 * these run on the real clock rather than a virtual test scheduler.
 */
class CompletableFutureAwaitTest {
  @Test
  fun `await throws TimeoutException when the future never completes`() = runBlocking {
    val never = CompletableFuture<Int>()
    try {
      never.await(100.milliseconds)
      fail("Expected the await to time out")
    } catch (e: TimeoutException) {
      // expected
    }
  }

  @Test
  fun `await returns the value when the future completes in time`() = runBlocking {
    assertEquals(42, CompletableFuture.completedFuture(42).await(5.seconds))
  }

  @Test
  fun `await propagates the failure when the future completes exceptionally`() = runBlocking {
    val failed =
        CompletableFuture<Int>().apply { completeExceptionally(IllegalStateException("nope")) }
    try {
      failed.await(5.seconds)
      fail("Expected the underlying failure to propagate")
    } catch (e: IllegalStateException) {
      // expected
    }
  }
}
