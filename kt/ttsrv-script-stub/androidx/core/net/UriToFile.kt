// androidx.core.net.toFile 的替身（就一行 Uri→File）
package androidx.core.net

import android.net.Uri
import java.io.File

fun Uri.toFile(): File = File(requireNotNull(path) { "Uri 没有路径: $this" })
