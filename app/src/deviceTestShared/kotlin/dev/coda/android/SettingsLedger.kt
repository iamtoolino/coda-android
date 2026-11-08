package dev.coda.android

/**
 * Record/restore logic behind [SystemSettings] (androidTest), kept free of Android types so a JVM test can drive it with a fake
 * device (source dir `src/deviceTestShared`, compiled into both the `test` and `androidTest` source sets).
 *
 * - The first value of a key is recorded before it is touched; "absent" (`settings get` prints `null`) is its own state and
 *   is restored by `settings delete`, never by writing a made-up value. A value that cannot be read at all aborts the change
 *   before anything is mutated, so an unknown original can never be turned into "enabled".
 * - Restoration is verified by reading the value back (radios and airplane mode: the on/off class, with a few settle retries).
 *   A failed or unverified restoration stays in the ledger for a retry; the remaining ones are still attempted; then
 *   [restoreAll] throws so the teardown fails instead of leaving the device changed unnoticed.
 *
 * [read] runs a read-only shell command and returns its output; [write] runs a mutating one (the caller gates it).
 */
class SettingsLedger(
    private val read: (String) -> String,
    private val write: (String) -> String,
    private val settle: () -> Unit = { Thread.sleep(300) },
    private val attempts: Int = 10,
) {
    private enum class Kind { SETTING, AIRPLANE, WIFI, DATA, DISPLAY }
    private class Entry(val kind: Kind, val namespace: String, val key: String, val original: String?)

    private val entries = LinkedHashMap<String, Entry>()

    /** Ids ("namespace/key") that still need restoring. */
    val pending: List<String> get() = entries.keys.toList()

    private fun isError(output: String) = output.contains("Exception") || output.contains("Error") ||
        output.startsWith("Unknown") || output.startsWith("Usage") || output.startsWith("cmd:")

    private fun get(namespace: String, key: String): String? {
        val out = read("settings get $namespace $key").trim()
        check(!isError(out)) { "cannot read $namespace/$key (refusing to change what cannot be restored): $out" }
        return out.takeUnless { it.isEmpty() || it == "null" }
    }

    private fun run(command: String) {
        val out = write(command).trim()
        check(!isError(out)) { "`$command` failed: $out" }
    }

    private fun record(kind: Kind, namespace: String, key: String) {
        val id = "$namespace/$key"
        if (id !in entries) entries[id] = Entry(kind, namespace, key, if (kind == Kind.DISPLAY) displayOverride() else get(namespace, key))
    }

    /** The `wm size` override (`WxH`), or null when the physical size applies. */
    private fun displayOverride(): String? {
        val out = read("wm size").trim()
        check(!isError(out) && out.contains("Physical size")) { "cannot read the display size (refusing to change it): $out" }
        return out.lines().firstOrNull { it.startsWith("Override size:") }?.substringAfter(':')?.trim()
    }

    /** `wm size <WxH>` (a 640 dp tall window); restored to the old override, or reset when there was none. */
    fun setDisplaySize(size: String) {
        require(Regex("\\d+x\\d+").matches(size)) { "size must be WxH: $size" }
        record(Kind.DISPLAY, "wm", "size")
        run("wm size $size")
    }

    /** `settings put <namespace> <key> <value>`. */
    fun put(namespace: String, key: String, value: String) {
        record(Kind.SETTING, namespace, key)
        run("settings put $namespace $key $value")
    }

    fun setAirplaneMode(enabled: Boolean) {
        record(Kind.AIRPLANE, "global", "airplane_mode_on")
        run("cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}")
    }

    /** Wi-Fi and mobile data together. Both originals are read before either is changed. */
    fun setRadios(enabled: Boolean) {
        record(Kind.WIFI, "global", "wifi_on")
        record(Kind.DATA, "global", "mobile_data")
        run("svc wifi ${if (enabled) "enable" else "disable"}")
        run("svc data ${if (enabled) "enable" else "disable"}")
    }

    private fun isOn(value: String?) = value != null && value != "0"

    private fun restoreOne(e: Entry) {
        if (e.kind == Kind.DISPLAY) {
            run("wm size ${e.original ?: "reset"}")
            val now = displayOverride()
            check(now == e.original) { "display size reads back as ${now ?: "physical"}, expected ${e.original ?: "physical"}" }
            return
        }
        val onOff = e.kind != Kind.SETTING
        if (e.original == null) {
            run("settings delete ${e.namespace} ${e.key}")
        } else if (!onOff) {
            run("settings put ${e.namespace} ${e.key} ${e.original}")
        } else {
            val state = if (isOn(e.original)) "enable" else "disable"
            run(when (e.kind) {
                Kind.AIRPLANE -> "cmd connectivity airplane-mode $state"
                Kind.WIFI -> "svc wifi $state"
                else -> "svc data $state"
            })
        }
        // Verify by reading back; radios and airplane mode settle asynchronously.
        repeat(if (onOff && e.original != null) attempts else 1) { attempt ->
            val now = get(e.namespace, e.key)
            val ok = if (onOff && e.original != null) isOn(now) == isOn(e.original) else now == e.original
            if (ok) return
            if (attempt < attempts - 1 && onOff) settle()
        }
        error("${e.namespace}/${e.key} reads back as ${get(e.namespace, e.key)}, expected ${e.original ?: "absent"}")
    }

    /**
     * Restores everything changed so far. Every restoration is attempted; the ones that fail stay recorded (retry with another
     * call) and the call then throws, listing them.
     */
    fun restoreAll() {
        val failures = mutableListOf<String>()
        for ((id, entry) in entries.entries.toList()) {
            try { restoreOne(entry); entries.remove(id) } catch (t: Exception) { failures += "$id: ${t.message}" }
        }
        check(failures.isEmpty()) { "system settings not restored (kept for retry): ${failures.joinToString("; ")}" }
    }
}
