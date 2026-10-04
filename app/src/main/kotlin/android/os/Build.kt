// Desktop stand-in for android.os.Build.
//
// The ported code gates newer-API paths on Build.VERSION.SDK_INT and reports
// the device in diagnostics and the device registry. Here SDK_INT is the
// newest level the Android app targets, so every "modern" branch is taken,
// and the identity fields describe the machine.
package android.os

object Build {
    object VERSION {
        const val SDK_INT: Int = 36
        @JvmField val RELEASE: String = System.getProperty("os.version").orEmpty()
        const val CODENAME: String = "REL"
    }

    object VERSION_CODES {
        const val O = 26; const val O_MR1 = 27; const val P = 28; const val Q = 29; const val R = 30
        const val S = 31; const val S_V2 = 32; const val TIRAMISU = 33; const val UPSIDE_DOWN_CAKE = 34
        const val VANILLA_ICE_CREAM = 35; const val BAKLAVA = 36
    }

    @JvmField val MANUFACTURER: String = System.getProperty("os.name").orEmpty()
    @JvmField val BRAND: String = System.getProperty("os.name").orEmpty()
    @JvmField val MODEL: String = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "desktop"
    @JvmField val DEVICE: String = System.getProperty("os.arch").orEmpty()
    @JvmField val PRODUCT: String = "tryptify-desktop"
    @JvmField val HARDWARE: String = System.getProperty("os.arch").orEmpty()
    @JvmField val SUPPORTED_ABIS: Array<String> = arrayOf(System.getProperty("os.arch").orEmpty())
}
