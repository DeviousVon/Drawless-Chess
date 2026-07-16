package android.content

import android.content.res.AssetManager
import java.io.File

/** JVM structural-check stub. The Android build uses android.content.Context. */
open class Context {
    open val applicationContext: Context get() = this
    open val noBackupFilesDir: File get() = File(".")
    open val assets: AssetManager get() = AssetManager()
    open fun getString(id: Int, vararg formatArgs: Any): String = ""
}
