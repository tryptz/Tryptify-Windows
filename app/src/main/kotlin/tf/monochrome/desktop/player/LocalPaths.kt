package tf.monochrome.desktop.player

/**
 * Desktop: a path shape the Android sources never met. A Windows path parses
 * as a URI whose scheme is the drive letter — `Uri.parse("C:\\Music\\a.flac")`
 * has scheme `C` — so every place that asks "is this a bare file path, not a
 * URI?" needs this check next to its `startsWith("/")`.
 */
internal object LocalPaths {
    private val windowsDrive = Regex("^[A-Za-z]:[\\\\/]")

    /** True for `C:\\…` and `C:/…`. */
    fun isWindowsDrivePath(s: String): Boolean = windowsDrive.containsMatchIn(s)
}
