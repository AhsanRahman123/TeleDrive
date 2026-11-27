package com.drdisagree.teledrive.desktop.ui

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.25 second-pass MF-3: [PdfDocumentSession] owns every document its
 * blocking loader produces — a load that finishes after its generation
 * was superseded (path change A→B) or after the session closed (viewer
 * disposed) is dropped and closed, never published, never leaked.
 */
class DesktopPdfViewerTest {

    private class FakeDocument : Closeable {
        val closed = AtomicBoolean(false)
        override fun close() {
            closed.set(true)
        }
    }

    @Test
    fun `a current load is published and closed with the session`() = runBlocking {
        val document = FakeDocument()
        val session = PdfDocumentSession<FakeDocument> { document }

        val result = session.load("a.pdf")

        assertEquals(PdfLoadResult.Loaded(document), result)
        assertFalse(document.closed.get())
        session.close()
        assertTrue(document.closed.get())
    }

    @Test
    fun `a newer published load closes the document it replaces`() = runBlocking {
        val first = FakeDocument()
        val second = FakeDocument()
        val session = PdfDocumentSession<FakeDocument> { path ->
            if (path == "a.pdf") first else second
        }

        session.load("a.pdf")
        val result = session.load("b.pdf")

        assertEquals(PdfLoadResult.Loaded(second), result)
        assertTrue(first.closed.get())
        assertFalse(second.closed.get())
        session.close()
        assertTrue(second.closed.get())
    }

    @Test
    fun `a load finishing after a newer load started is dropped and closed`() = runBlocking {
        val staleDocument = FakeDocument()
        val currentDocument = FakeDocument()
        val staleEntered = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)
        val session = PdfDocumentSession<FakeDocument> { path ->
            if (path == "a.pdf") {
                staleEntered.countDown()
                releaseStale.await(10, TimeUnit.SECONDS)
                staleDocument
            } else {
                currentDocument
            }
        }

        val staleLoad = async(Dispatchers.Default) { session.load("a.pdf") }
        assertTrue(staleEntered.await(10, TimeUnit.SECONDS))

        // Path changes A -> B while A's blocking load is still running.
        val currentResult = session.load("b.pdf")
        assertEquals(PdfLoadResult.Loaded(currentDocument), currentResult)

        releaseStale.countDown()
        val staleResult = staleLoad.await()

        // A's document was never published and was closed on arrival;
        // B's document is untouched.
        assertEquals(PdfLoadResult.Stale, staleResult)
        assertTrue(staleDocument.closed.get())
        assertFalse(currentDocument.closed.get())
        session.close()
        assertTrue(currentDocument.closed.get())
    }

    @Test
    fun `a load finishing after the session closed is dropped and closed`() = runBlocking {
        val document = FakeDocument()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = PdfDocumentSession<FakeDocument> {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            document
        }

        val load = async(Dispatchers.Default) { session.load("a.pdf") }
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        // The viewer leaves before the blocking load finishes.
        session.close()
        release.countDown()

        assertEquals(PdfLoadResult.Stale, load.await())
        assertTrue(document.closed.get())
    }

    @Test
    fun `a failing load reports failed while its generation is current`() = runBlocking {
        val session = PdfDocumentSession<FakeDocument> {
            throw IllegalStateException("not a pdf")
        }

        assertEquals(PdfLoadResult.Failed, session.load("broken.pdf"))
        session.close()
    }
}
