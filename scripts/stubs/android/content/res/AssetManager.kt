package android.content.res

import java.io.ByteArrayInputStream
import java.io.InputStream

/** JVM structural-check stub. No asset bytes are consumed by the compile-only gate. */
open class AssetManager {
    open fun open(fileName: String, accessMode: Int): InputStream = ByteArrayInputStream(byteArrayOf())

    companion object {
        const val ACCESS_STREAMING: Int = 2
    }
}
