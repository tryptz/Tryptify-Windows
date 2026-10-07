package tf.monochrome.desktop.platform.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import tf.monochrome.desktop.dj.controller.HidBus
import tf.monochrome.desktop.dj.controller.HidConnection
import tf.monochrome.desktop.dj.controller.HidInfo
import tf.monochrome.desktop.dj.controller.HidOpenException

/**
 * USB HID on Windows, through the system's own HID class driver: setupapi
 * lists the devices, kernel32 opens and talks to them, hid.dll says how long
 * their reports are. Nothing to install; a controller works the moment
 * Windows has enumerated it.
 *
 * All I/O is overlapped (asynchronous) and every wait has a timeout, so a
 * controller unplugged mid-read is an [IOException] rather than a thread
 * blocked forever. The pattern is hidapi's: one read stays pending across
 * calls, so no report arriving between two calls is lost.
 *
 * The structures are laid out by hand rather than with JNA's Structure: there
 * are four, each needs one or two fields, and their sizes differ between
 * 32- and 64-bit Windows only by the pointer size.
 */
object WindowsHid : HidBus {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    // setupapi
    private const val DIGCF_PRESENT = 0x2
    private const val DIGCF_DEVICEINTERFACE = 0x10
    // kernel32
    private const val GENERIC_READ_WRITE = 0xC0000000.toInt()
    private const val FILE_SHARE_READ_WRITE = 0x3
    private const val OPEN_EXISTING = 3
    private const val FILE_FLAG_OVERLAPPED = 0x40000000
    private const val WAIT_OBJECT_0 = 0
    private const val WAIT_TIMEOUT = 0x102
    // winerror.h
    private const val ERROR_ACCESS_DENIED = 5
    private const val ERROR_SHARING_VIOLATION = 32
    private const val ERROR_IO_PENDING = 997
    // hidclass.h
    private const val IOCTL_HID_GET_FEATURE = 0xB0192
    private const val IOCTL_HID_GET_INPUT_REPORT = 0xB01A2
    private const val HIDP_STATUS_SUCCESS = 0x00110000

    /** Far more HID interfaces than any machine has; only a guard against a list that never ends. */
    private const val MAX_INTERFACES = 1024
    private const val INPUT_BUFFERS = 64
    private const val WRITE_TIMEOUT_MS = 100
    private const val IOCTL_TIMEOUT_MS = 500

    // Not Native.POINTER_SIZE: that loads JNA's DLL as soon as this object is
    // built, during startup, and Windows can block the DLL; Platform asks the JVM.
    private val pointerSize = if (Platform.is64Bit()) 8 else 4
    /** SP_DEVICE_INTERFACE_DATA: cbSize, the class GUID, flags, a ULONG_PTR. */
    private val interfaceDataSize = if (pointerSize == 8) 32 else 28
    /** SP_DEVICE_INTERFACE_DETAIL_DATA_W's cbSize: setupapi packs it to 8 on x64, 1 on x86. */
    private val detailHeaderSize = if (pointerSize == 8) 8 else 6
    /** OVERLAPPED: Internal, InternalHigh (ULONG_PTR each), Offset/OffsetHigh, hEvent. */
    private val overlappedSize = 3L * pointerSize + if (pointerSize == 8) 8 else 4
    private val overlappedEvent = 2L * pointerSize + 8

    @Suppress("FunctionName")
    private interface SetupApi : StdCallLibrary {
        fun SetupDiGetClassDevsW(guid: Pointer, enumerator: Pointer?, parent: Pointer?, flags: Int): Pointer?
        fun SetupDiEnumDeviceInterfaces(set: Pointer, info: Pointer?, guid: Pointer, index: Int, data: Pointer): Boolean
        fun SetupDiGetDeviceInterfaceDetailW(
            set: Pointer, data: Pointer, detail: Pointer?, size: Int, required: IntByReference?, info: Pointer?,
        ): Boolean
        fun SetupDiDestroyDeviceInfoList(set: Pointer): Boolean
    }

    /** hid.dll's HidD_ calls return BOOLEAN, one byte, not a 4-byte BOOL. */
    @Suppress("FunctionName")
    private interface HidApi : StdCallLibrary {
        fun HidD_GetHidGuid(guid: Pointer)
        fun HidD_GetAttributes(handle: Pointer, attributes: Pointer): Byte
        fun HidD_GetPreparsedData(handle: Pointer, data: PointerByReference): Byte
        fun HidD_FreePreparsedData(data: Pointer): Byte
        fun HidP_GetCaps(data: Pointer, caps: Pointer): Int
        fun HidD_SetNumInputBuffers(handle: Pointer, count: Int): Byte
    }

    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun CreateFileW(
            name: WString, access: Int, share: Int, security: Pointer?, disposition: Int, flags: Int, template: Pointer?,
        ): Pointer?
        fun ReadFile(handle: Pointer, buffer: Pointer, length: Int, read: IntByReference?, overlapped: Pointer): Boolean
        fun WriteFile(handle: Pointer, buffer: Pointer, length: Int, written: IntByReference?, overlapped: Pointer): Boolean
        fun DeviceIoControl(
            handle: Pointer, code: Int, inBuffer: Pointer?, inSize: Int, outBuffer: Pointer?, outSize: Int,
            returned: IntByReference?, overlapped: Pointer,
        ): Boolean
        fun GetOverlappedResult(handle: Pointer, overlapped: Pointer, transferred: IntByReference, wait: Boolean): Boolean
        fun CancelIoEx(handle: Pointer, overlapped: Pointer?): Boolean
        fun CreateEventW(security: Pointer?, manualReset: Boolean, initialState: Boolean, name: WString?): Pointer?
        fun ResetEvent(event: Pointer): Boolean
        fun WaitForSingleObject(handle: Pointer, milliseconds: Int): Int
        fun CloseHandle(handle: Pointer): Boolean
    }

    private class Apis(val setup: SetupApi, val hid: HidApi, val kernel: Kernel32, val hidGuid: Memory)

    private val apis: Apis? by lazy {
        if (!windows) null else runCatching {
            val hid = Native.load("hid", HidApi::class.java)
            val guid = Memory(16).also { hid.HidD_GetHidGuid(it) }
            Apis(
                Native.load("setupapi", SetupApi::class.java),
                hid,
                Native.load("kernel32", Kernel32::class.java),
                guid,
            )
        }.getOrNull()
    }

    /** VID and PID by interface path: paths do not change while a device stays plugged in. */
    private val ids = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val pathIds = Regex("vid_([0-9a-f]{4})&pid_([0-9a-f]{4})", RegexOption.IGNORE_CASE)

    override fun devices(): List<HidInfo> {
        val api = apis ?: return emptyList()
        val set = api.setup.SetupDiGetClassDevsW(api.hidGuid, null, null, DIGCF_PRESENT or DIGCF_DEVICEINTERFACE)
        if (set == null || isInvalid(set)) return emptyList()
        val found = ArrayList<HidInfo>()
        try {
            val data = Memory(interfaceDataSize.toLong())
            val required = IntByReference()
            for (index in 0 until MAX_INTERFACES) {
                data.clear()
                data.setInt(0, interfaceDataSize)
                if (!api.setup.SetupDiEnumDeviceInterfaces(set, null, api.hidGuid, index, data)) break
                // First call: how big the path is. Second: the path.
                api.setup.SetupDiGetDeviceInterfaceDetailW(set, data, null, 0, required, null)
                if (required.value <= detailHeaderSize) continue
                val detail = Memory(required.value.toLong())
                detail.setInt(0, detailHeaderSize)
                if (!api.setup.SetupDiGetDeviceInterfaceDetailW(set, data, detail, required.value, null, null)) continue
                val path = detail.getWideString(4)
                val (vid, pid) = ids[path] ?: identify(api, path)?.also { ids[path] = it } ?: continue
                found += HidInfo(path, vid, pid)
            }
        } finally {
            api.setup.SetupDiDestroyDeviceInfoList(set)
        }
        return found
    }

    /**
     * USB paths carry `vid_xxxx&pid_xxxx`. Others (Bluetooth) do not, so the
     * device is asked: opened with no access, which even a keyboard Windows
     * holds for itself allows.
     */
    private fun identify(api: Apis, path: String): Pair<Int, Int>? {
        pathIds.find(path)?.let { m ->
            return m.groupValues[1].toInt(16) to m.groupValues[2].toInt(16)
        }
        val handle = api.kernel.CreateFileW(WString(path), 0, FILE_SHARE_READ_WRITE, null, OPEN_EXISTING, 0, null)
        if (handle == null || isInvalid(handle)) return null
        try {
            // HIDD_ATTRIBUTES: ULONG Size, USHORT VendorID, ProductID, VersionNumber.
            val attributes = Memory(12).apply { clear(); setInt(0, 12) }
            if (api.hid.HidD_GetAttributes(handle, attributes).toInt() == 0) return null
            return (attributes.getShort(4).toInt() and 0xFFFF) to (attributes.getShort(6).toInt() and 0xFFFF)
        } finally {
            api.kernel.CloseHandle(handle)
        }
    }

    override fun open(info: HidInfo): HidConnection {
        val api = apis ?: throw HidOpenException(busy = false, "HID is not available")
        val handle = api.kernel.CreateFileW(
            WString(info.path), GENERIC_READ_WRITE, FILE_SHARE_READ_WRITE, null, OPEN_EXISTING, FILE_FLAG_OVERLAPPED, null,
        )
        if (handle == null || isInvalid(handle)) {
            val error = Native.getLastError()
            throw HidOpenException(
                busy = error == ERROR_SHARING_VIOLATION || error == ERROR_ACCESS_DENIED,
                "CreateFile failed, error $error",
            )
        }
        val caps = caps(api, handle)
        if (caps == null) {
            api.kernel.CloseHandle(handle)
            throw HidOpenException(busy = false, "HidP_GetCaps failed")
        }
        api.hid.HidD_SetNumInputBuffers(handle, INPUT_BUFFERS)
        return Connection(api, handle, caps[0], caps[1], caps[2])
    }

    /** Input, output and feature report lengths, ID byte included. */
    private fun caps(api: Apis, handle: Pointer): IntArray? {
        val ref = PointerByReference()
        if (api.hid.HidD_GetPreparsedData(handle, ref).toInt() == 0) return null
        val preparsed = ref.value ?: return null
        try {
            // HIDP_CAPS: Usage, UsagePage, then the three lengths as USHORTs.
            val caps = Memory(64).apply { clear() }
            if (api.hid.HidP_GetCaps(preparsed, caps) != HIDP_STATUS_SUCCESS) return null
            return IntArray(3) { caps.getShort(4L + 2 * it).toInt() and 0xFFFF }
        } finally {
            api.hid.HidD_FreePreparsedData(preparsed)
        }
    }

    private fun isInvalid(handle: Pointer) = Pointer.nativeValue(handle) == -1L

    /** One overlapped operation's OVERLAPPED block and its manual-reset event. */
    private class Overlapped(private val kernel: Kernel32) {
        val event: Pointer = kernel.CreateEventW(null, true, false, null)
            ?: throw IOException("CreateEvent failed, error ${Native.getLastError()}")
        val block = Memory(overlappedSize)

        /** Ready for the next operation: Windows wants the block zeroed and the event unsignalled. */
        fun reset(): Pointer {
            block.clear()
            block.setPointer(overlappedEvent, event)
            kernel.ResetEvent(event)
            return block
        }

        fun close() {
            kernel.CloseHandle(event)
        }
    }

    private class Connection(
        private val api: Apis,
        private val handle: Pointer,
        private val inputLength: Int,
        private val outputLength: Int,
        private val featureLength: Int,
    ) : HidConnection {
        private val kernel = api.kernel
        private val readLock = Any()
        private val writeLock = Any()
        private val ioctlLock = Any()
        private val reading = Overlapped(kernel)
        private val writing = Overlapped(kernel)
        private val ioctl = Overlapped(kernel)
        private val readBuffer = Memory(maxOf(inputLength, 1).toLong())
        private var writeBuffer = Memory(maxOf(outputLength, 1).toLong())
        private var readPending = false
        @Volatile private var closed = false

        override fun read(buffer: ByteArray, timeoutMs: Int): Int = synchronized(readLock) {
            if (closed) throw IOException("closed")
            if (!readPending) {
                val ok = kernel.ReadFile(handle, readBuffer, inputLength, null, reading.reset())
                if (!ok) {
                    val error = Native.getLastError()
                    if (error != ERROR_IO_PENDING) throw IOException("ReadFile failed, error $error")
                }
                // Completed or pending, the event is signalled when the report is in.
                readPending = true
            }
            when (kernel.WaitForSingleObject(reading.event, timeoutMs)) {
                WAIT_OBJECT_0 -> Unit
                WAIT_TIMEOUT -> return 0
                else -> throw IOException("wait failed, error ${Native.getLastError()}")
            }
            readPending = false
            val count = IntByReference()
            if (!kernel.GetOverlappedResult(handle, reading.block, count, false)) {
                throw IOException("read failed, error ${Native.getLastError()}")
            }
            val n = minOf(count.value, buffer.size)
            readBuffer.read(0, buffer, 0, n)
            n
        }

        override fun write(report: ByteArray): Boolean = synchronized(writeLock) {
            if (closed || report.isEmpty()) return false
            // Windows takes output reports at exactly the longest length the
            // device declares; a shorter report goes out zero-padded.
            val length = maxOf(report.size, outputLength)
            if (writeBuffer.size() < length) writeBuffer = Memory(length.toLong())
            writeBuffer.clear()
            writeBuffer.write(0, report, 0, report.size)
            val ok = kernel.WriteFile(handle, writeBuffer, length, null, writing.reset())
            if (!ok && Native.getLastError() != ERROR_IO_PENDING) return false
            finish(writing, WRITE_TIMEOUT_MS) >= 0
        }

        override fun feature(id: Int): ByteArray? = request(IOCTL_HID_GET_FEATURE, id, featureLength)

        override fun inputReport(id: Int): ByteArray? = request(IOCTL_HID_GET_INPUT_REPORT, id, inputLength)

        /**
         * A report asked for: DeviceIoControl, overlapped, as hidapi does,
         * since HidD_GetFeature on an overlapped handle would wait forever on
         * a device that never answers.
         */
        private fun request(code: Int, id: Int, length: Int): ByteArray? = synchronized(ioctlLock) {
            if (closed || length <= 0) return null
            val buffer = Memory(length.toLong()).apply { clear(); setByte(0, id.toByte()) }
            val ok = kernel.DeviceIoControl(handle, code, buffer, length, buffer, length, null, ioctl.reset())
            if (!ok && Native.getLastError() != ERROR_IO_PENDING) return null
            var n = finish(ioctl, IOCTL_TIMEOUT_MS)
            if (n <= 0) return null
            // Without numbered reports the count leaves out the 0 in byte 0.
            if (id == 0) n++
            buffer.getByteArray(0, minOf(n, length))
        }

        /**
         * Waits for an operation; on timeout cancels it and waits for the
         * cancel, so the buffer it writes into is never freed under it.
         * Returns the byte count, or -1.
         */
        private fun finish(op: Overlapped, timeoutMs: Int): Int {
            val count = IntByReference()
            if (kernel.WaitForSingleObject(op.event, timeoutMs) != WAIT_OBJECT_0) {
                kernel.CancelIoEx(handle, op.block)
                kernel.GetOverlappedResult(handle, op.block, count, true)
                return -1
            }
            return if (kernel.GetOverlappedResult(handle, op.block, count, false)) count.value else -1
        }

        override fun close() {
            synchronized(readLock) {
                synchronized(writeLock) {
                    synchronized(ioctlLock) {
                        if (closed) return
                        closed = true
                        if (readPending) {
                            kernel.CancelIoEx(handle, reading.block)
                            kernel.GetOverlappedResult(handle, reading.block, IntByReference(), true)
                            readPending = false
                        }
                        kernel.CloseHandle(handle)
                        reading.close()
                        writing.close()
                        ioctl.close()
                    }
                }
            }
        }
    }
}
