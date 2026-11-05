package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesTest {
    @Test
    fun theTypeComesFromTheResolverOrTheNameElseBytes() {
        assertEquals("image/png", Files.type("image/png", "shot.png"))
        assertEquals("image/png", Files.type("IMAGE/PNG; charset=binary", "x"))
        assertEquals("image/jpeg", Files.type(null, "photo.JPG"))
        assertEquals("image/jpeg", Files.type("application/octet-stream", "photo.jpeg"))
        assertEquals("text/markdown", Files.type(null, "notes.md"))
        assertEquals("application/json", Files.type("", "a.b.json"))
        assertEquals("application/pdf", Files.type("application/pdf", null))
        assertEquals("application/zip", Files.type(null, "tool.zip"))
        assertEquals("application/zip", Files.type("application/zip", "x"))
        for (other in listOf("image/svg+xml" to "a.svg", "text/html" to "a.html", "application/x-sh" to "a.sh", null to "a.exe", null to "noext", null to "dot.", "text/x-python" to "a.py")) {
            assertEquals("${other.first} ${other.second}", Files.OCTET, Files.type(other.first, other.second))
        }
        assertEquals("text/plain", Files.type("text/plain", "notes"))
        assertEquals(9, Files.MIMES.size)
    }

    @Test
    fun theExtensionIsOneShortLowercaseWordOrNothing() {
        assertEquals("pcap", Files.ext("capture.pcap"))
        assertEquals("py", Files.ext("a.PY"))
        assertEquals("gz", Files.ext("x.TAR.GZ"))
        assertEquals("abcdefgh", Files.ext("x.abcdefgh"))
        assertEquals("png", Files.ext("shot.png"))
        for (bad in listOf("../../x.sh/", "x.", ".bashrc", "x.abcdefghi", "x.p y", "Makefile", "x.p/y", "x.é", "x.a_b", "x.a-b", null, "")) {
            assertNull(bad, Files.ext(bad))
        }
    }

    @Test
    fun theCapIsEightMiBAndSizesRead() {
        assertTrue(Files.fits(0))
        assertTrue(Files.fits(8L shl 20))
        assertFalse(Files.fits((8L shl 20) + 1))
        assertFalse(Files.fits(-1))
        assertEquals("48 B", Files.size(48))
        assertEquals("2.1 KiB", Files.size(2150))
        assertEquals("8.0 MiB", Files.size(8L shl 20))
    }

    @Test
    fun theProgressLineShowsACleanShortName() {
        assertEquals("⇪ shot.png · 2.1 KiB…", Files.progress("shot.png", 2150))
        assertEquals("⇪ file · 1 B…", Files.progress("\u001b[31m\u0007", 1))
        val long = "a".repeat(80) + ".png"
        assertEquals(Files.MAX_NAME, Files.shown(long).length)
        assertTrue(Files.shown(long).endsWith("…"))
    }

    @Test
    fun thePathLandsOnTheLineWithASpaceAfterIt() {
        val path = "/home/alice/.config/muxway/agent/inbox/20261025-102235-56fwge.png"
        assertEquals("$path ", Files.onLine("", path))
        assertEquals("what is wrong in $path ", Files.onLine("what is wrong in", path))
        assertEquals("what is wrong in $path ", Files.onLine("what is wrong in ", path))
        assertEquals("a $path b $path ", Files.onLine(Files.onLine("a", path) + "b", path))
        assertEquals("first\n$path ", Files.onLine("first\n", path))
    }

    @Test
    fun theMultipartBodyHasOnePartNamedFileAndNeverThePhonesName() {
        val data = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), 0, 0x1a, '-'.code.toByte(), '-'.code.toByte())
        val m = Files.multipart(data, "image/png", boundary = "boundary0123456789ab")
        assertEquals("multipart/form-data; boundary=boundary0123456789ab", m.contentType)
        val head = "--boundary0123456789ab\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file\"\r\nContent-Type: image/png\r\n\r\n".toByteArray()
        val tail = "\r\n--boundary0123456789ab--\r\n".toByteArray()
        assertEquals(head.size + data.size + tail.size, m.body.size)
        assertTrue(head.contentEquals(m.body.copyOfRange(0, head.size)))
        assertTrue(data.contentEquals(m.body.copyOfRange(head.size, head.size + data.size)))
        assertTrue(tail.contentEquals(m.body.copyOfRange(head.size + data.size, m.body.size)))
        // A boundary the caller did not choose is long, random and plain.
        val a = Files.multipart(data, "text/plain")
        val b = Files.multipart(data, "text/plain")
        val boundaryOf = { x: Files.Multipart -> x.contentType.substringAfter("boundary=") }
        assertEquals(32, boundaryOf(a).length)
        assertTrue(boundaryOf(a).all { it.isLetterOrDigit() })
        assertFalse(boundaryOf(a) == boundaryOf(b))
        assertThrows(IllegalArgumentException::class.java) { Files.multipart(data, "image/svg+xml") }
        assertThrows(IllegalArgumentException::class.java) { Files.multipart(data, Files.OCTET, "a/b") }
        assertThrows(IllegalArgumentException::class.java) { Files.multipart(data, Files.OCTET, "Py") }
        // Bytes of a kind the host does not know travel with the name's extension and nothing else of the name.
        val any = Files.multipart(data, Files.OCTET, "pcap", "boundary0123456789ab")
        assertTrue(String(any.body, Charsets.ISO_8859_1).startsWith("--boundary0123456789ab\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file.pcap\"\r\nContent-Type: application/octet-stream\r\n\r\n"))
        assertThrows(IllegalArgumentException::class.java) { Files.multipart(ByteArray(Files.MAX_BYTES + 1), "text/plain") }
        assertThrows(IllegalArgumentException::class.java) { Files.multipart(data, "text/plain", boundary = "short") }
        assertThrows(IllegalArgumentException::class.java) { Files.multipart(data, "text/plain", "has-a-dash-and-is-long-enough") }
    }
}
