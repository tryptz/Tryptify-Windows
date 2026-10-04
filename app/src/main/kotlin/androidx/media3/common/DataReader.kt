// Desktop stand-in for Media3's DataReader.
package androidx.media3.common

import java.io.IOException

interface DataReader {
    /** Reads up to [length] bytes; returns the count or C.RESULT_END_OF_INPUT. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}
