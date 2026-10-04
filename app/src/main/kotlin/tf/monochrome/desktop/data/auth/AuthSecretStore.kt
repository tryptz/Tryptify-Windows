package tf.monochrome.desktop.data.auth

import android.util.Log
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.desktop.platform.AppPaths

/**
 * A small key/value file for sign-in secrets, encrypted for the Windows user.
 *
 * Android kept these in app-private SharedPreferences, which no other app can
 * read. The desktop has no sandbox, and Supabase-kt's JVM default would have
 * put the session (refresh token included) in plaintext under the shared root
 * of `java.util.prefs` — the registry key every Java program on the machine
 * reads and writes. So on Windows the file is sealed with DPAPI
 * (`CryptProtectData`, user scope): only this Windows account on this machine
 * can open it, with no key for the app to manage. Elsewhere (the Linux build
 * machine's smoke tests) it is a plain file readable by its owner only.
 *
 * A file that cannot be opened (copied from another machine, or after an
 * administrator reset the account's password) reads as empty, which the
 * callers treat as "signed out". If DPAPI itself is unavailable the value is
 * written unprotected with a warning rather than lost, matching what Android
 * stored.
 */
internal class AuthSecretStore(private val file: File) {

    private val lock = Any()

    fun get(key: String): String? = synchronized(lock) {
        read()[key]?.jsonPrimitive?.contentOrNull
    }

    /** Sets [key] to [value], or removes it when [value] is null. */
    fun put(key: String, value: String?) {
        synchronized(lock) {
            val current = read().toMutableMap()
            if (value == null) {
                current.remove(key)
            } else {
                current[key] = JsonPrimitive(value)
            }
            write(JsonObject(current))
        }
    }

    private fun read(): JsonObject {
        if (!file.isFile) return EMPTY
        return runCatching {
            val bytes = file.readBytes()
            val clear = when {
                bytes.startsWith(MAGIC_DPAPI) -> unprotect(bytes.copyOfRange(MAGIC_DPAPI.size, bytes.size))
                bytes.startsWith(MAGIC_PLAIN) -> bytes.copyOfRange(MAGIC_PLAIN.size, bytes.size)
                else -> return EMPTY
            }
            Json.parseToJsonElement(clear.decodeToString()).jsonObject
        }.getOrElse {
            Log.w(TAG, "Stored sign-in data in ${file.name} could not be opened; treating it as signed out (${it.message})")
            EMPTY
        }
    }

    private fun write(content: JsonObject) {
        if (content.isEmpty()) {
            file.delete()
            return
        }
        val clear = content.toString().toByteArray(Charsets.UTF_8)
        val sealed = if (AppPaths.isWindows) {
            runCatching { MAGIC_DPAPI + protect(clear) }.getOrElse {
                Log.w(TAG, "DPAPI unavailable (${it.message}); storing ${file.name} unprotected")
                MAGIC_PLAIN + clear
            }
        } else {
            MAGIC_PLAIN + clear
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealed)
        restrictToOwner(tmp)
        runCatching {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // Only reached on Windows, so JNA's Crypt32 binding is never loaded elsewhere.
    private fun protect(clear: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(clear, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)

    private fun unprotect(sealed: ByteArray): ByteArray =
        Crypt32Util.cryptUnprotectData(sealed, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)

    private fun restrictToOwner(target: File) {
        runCatching {
            if ("posix" in target.toPath().fileSystem.supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(
                    target.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
        }
        // On Windows the file lives under %LOCALAPPDATA%, which is already
        // private to the account; the DPAPI seal is the protection that counts.
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        const val TAG = "AuthSecretStore"
        val EMPTY = JsonObject(emptyMap())
        val MAGIC_DPAPI = "TRYPTIFY-DPAPI1\n".toByteArray(Charsets.US_ASCII)
        val MAGIC_PLAIN = "TRYPTIFY-PLAIN1\n".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Supabase's session storage over [AuthSecretStore], replacing the JVM default
 * (`java.util.prefs`, plaintext, shared root node). Same JSON shape the
 * library's own `SettingsSessionManager` writes; `encodeDefaults` must stay on
 * or a reloaded session loses fields the library expects.
 */
internal class SecureSessionManager(private val store: AuthSecretStore) : SessionManager {

    override suspend fun saveSession(session: UserSession) {
        store.put(KEY, sessionJson.encodeToString(UserSession.serializer(), session))
    }

    override suspend fun loadSession(): UserSession {
        val raw = store.get(KEY) ?: throw NoSessionFoundException()
        return sessionJson.decodeFromString(UserSession.serializer(), raw)
    }

    override suspend fun deleteSession() {
        store.put(KEY, null)
    }

    private companion object {
        const val KEY = "supabase_session"
        val sessionJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
