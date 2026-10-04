package tf.monochrome.desktop.performance

import android.os.Build
import java.io.File
import java.lang.management.ManagementFactory

data class DeviceSnapshot(
    val cores: Int,
    val bigCores: Int,
    val maxFreqMhz: Int,
    val ramMb: Int,
    val abi: String,
    val sdk: Int,
)

/**
 * Synchronous, no-Context machine probe. Runs from [tf.monochrome.desktop.MonochromeApp]'s
 * initialiser, which main() touches first — before `Dispatchers.Default` is first
 * used, because the coroutine scheduler reads its pool sizes exactly once. Every
 * probe is defensive so an unreadable value can't crash startup.
 *
 * Desktop: Android read core frequencies from `cpufreq` sysfs and RAM from
 * `/proc/meminfo`. On Windows neither exists, so RAM comes from the JVM's
 * `OperatingSystemMXBean` (physical memory, not the heap) and the core count from
 * `availableProcessors` (logical processors, so SMT threads count). The sysfs
 * frequency read is kept for Linux, where it works; on Windows the frequency is
 * reported as unknown (0) and big-core counting is skipped, which [classify]
 * already treats as "no data" rather than "weak".
 */
object DeviceCapabilities {

    @Volatile
    private var cached: Pair<DeviceTier, DeviceSnapshot>? = null

    fun detect(): Pair<DeviceTier, DeviceSnapshot> {
        cached?.let { return it }
        val snapshot = probe()
        val tier = classify(snapshot)
        val result = tier to snapshot
        cached = result
        return result
    }

    /** The profile for this machine; the same value `MonochromeApp.profile` holds. */
    fun profile(): PerformanceProfile = PerformanceProfile.forTier(detect().first)

    private fun probe(): DeviceSnapshot {
        val cores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(2)
        val freqs = readCoreMaxFreqsMhz(cores)
        val maxFreq = freqs.maxOrNull() ?: 0
        val bigCores = if (maxFreq > 0) freqs.count { it >= maxFreq - BIG_CLUSTER_TOLERANCE_MHZ } else 0
        val ramMb = readTotalRamMb()
        val abi = runCatching { Build.SUPPORTED_ABIS.firstOrNull().orEmpty() }.getOrDefault("")
        val sdk = Build.VERSION.SDK_INT
        return DeviceSnapshot(
            cores = cores,
            bigCores = bigCores,
            maxFreqMhz = maxFreq,
            ramMb = ramMb,
            abi = abi,
            sdk = sdk,
        )
    }

    /** Linux only (cpufreq sysfs); on Windows every read comes back 0, "unknown". */
    private fun readCoreMaxFreqsMhz(cores: Int): IntArray {
        if (cores <= 0) return IntArray(0)
        val out = IntArray(cores)
        for (i in 0 until cores) {
            out[i] = runCatching {
                val node = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (!node.canRead()) return@runCatching 0
                // cpuinfo_max_freq is reported in kHz.
                node.readText().trim().toLongOrNull()?.let { (it / 1000L).toInt() } ?: 0
            }.getOrDefault(0)
        }
        return out
    }

    /** Installed physical memory, from the JVM's platform MXBean; 0 if unavailable. */
    private fun readTotalRamMb(): Int = runCatching {
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            ?: return@runCatching 0
        (os.totalMemorySize / (1024L * 1024L)).toInt()
    }.getOrDefault(0)

    private fun classify(s: DeviceSnapshot): DeviceTier {
        // Skia's software rasteriser (forced with -Dskiko.renderApi=SOFTWARE or
        // SKIKO_RENDER_API, typically on a machine whose GPU driver fails) does
        // every blur on the CPU; the glass costs more than it is worth there.
        if (softwareRendering()) return DeviceTier.LOW

        // Missing data must stay neutral: a reading of 0 means "unknown", never
        // "small", so an unreadable value cannot push a capable machine down.
        val freqKnown = s.maxFreqMhz > 0

        // LOW: any single "too weak" signal wins, so we err toward smoothness.
        val ramLow = s.ramMb in 1..LOW_RAM_MB_MAX
        val weakCpu = freqKnown && s.bigCores == 0 && s.maxFreqMhz <= LOW_FREQ_MHZ_MAX
        if (s.cores <= LOW_CORES_MAX || ramLow || weakCpu) return DeviceTier.LOW

        // HIGH: many hardware threads and plenty of RAM. Desktop CPUs have no
        // big.LITTLE frequency split worth counting, so the Android big-core
        // requirement only applies when cpufreq data exists.
        val manyCores = s.cores >= HIGH_CORES_MIN
        val manyBig = !freqKnown || s.bigCores >= HIGH_BIG_CORES_MIN
        val plentyRam = s.ramMb == 0 || s.ramMb >= HIGH_RAM_MB_MIN
        if (manyCores && manyBig && plentyRam) return DeviceTier.HIGH

        return DeviceTier.MID
    }

    private fun softwareRendering(): Boolean =
        System.getProperty("skiko.renderApi").orEmpty().equals("SOFTWARE", ignoreCase = true) ||
            System.getenv("SKIKO_RENDER_API").orEmpty().equals("SOFTWARE", ignoreCase = true)

    // "Same cluster" tolerance for big-core counting. Kryo/Cortex clusters typically
    // differ by 200+ MHz; this keeps us tolerant of dvfs governor noise.
    private const val BIG_CLUSTER_TOLERANCE_MHZ = 100

    // Desktop thresholds. Android's LOW tier (≤ 4 cores, ≤ 3 GB) was for phones
    // with four slow cores; a PC reporting 4 logical processors is a dual-core
    // with SMT and handles the glass at MID. Only ≤ 2 logical processors, or
    // 4 GB of RAM or less (Windows 11's own floor), is LOW. The HIGH bar sits a
    // little under 8 GB because firmware and integrated graphics reserve some of
    // it, so an 8 GB machine reports roughly 7.8.
    private const val LOW_CORES_MAX = 2
    private const val LOW_RAM_MB_MAX = 4 * 1024
    private const val LOW_FREQ_MHZ_MAX = 1800

    private const val HIGH_CORES_MIN = 8
    private const val HIGH_BIG_CORES_MIN = 3
    private const val HIGH_RAM_MB_MIN = 8 * 1024 - 512
}
