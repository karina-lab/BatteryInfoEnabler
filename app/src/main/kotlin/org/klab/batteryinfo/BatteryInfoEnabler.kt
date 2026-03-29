package org.klab.batteryinfo

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Enumeration
import dalvik.system.DexFile

@SuppressLint("PrivateApi", "BlockedPrivateApi")
class BatteryInfoEnabler : XposedModule() {

    private var batteryHealthData: Class<*>? = null
    private var batteryHealthStatus: Class<*>? = null
    private var batteryFeatureFlags: Class<*>? = null
    private var batteryHealthUtils: Class<*>? = null
    private var reduced = false

    private companion object {
        const val TAG = "BatteryInfoEnabler"
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName == "com.android.settings") {
            hookSettings(param)
        }
        if (param.packageName != "com.google.android.settings.intelligence") return

        if (findClassesIntelligence(param)) {
            log(Log.INFO, TAG, "Found classes for Settings Services")
            hookIntelligence()
        } else {
            log(Log.ERROR, TAG, "Failed to find classes for Settings Services")
        }
    }

    private fun hookSettings(lpparam: XposedModuleInterface.PackageLoadedParam) {
        try {
            val googleProvider = lpparam.defaultClassLoader.loadClass("com.google.android.settings.fuelgauge.BatterySettingsFeatureProviderGoogleImpl")
            
            val hookReturnTrue = object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any {
                    return true
                }
            }

            try {
                val m1 = googleProvider.getDeclaredMethod("isBatteryInfoEnabled", Context::class.java)
                hook(m1).intercept(hookReturnTrue)
                val m2 = googleProvider.getDeclaredMethod("isManufactureDateAvailable", Context::class.java, Long::class.javaPrimitiveType)
                hook(m2).intercept(hookReturnTrue)
                val m3 = googleProvider.getDeclaredMethod("isFirstUseDateAvailable", Context::class.java, Long::class.javaPrimitiveType)
                hook(m3).intercept(hookReturnTrue)
            } catch (ignored: NoSuchMethodException) {
                // Fallback for different signatures
                googleProvider.declaredMethods.forEach { m ->
                    if (m.name in listOf("isBatteryInfoEnabled", "isManufactureDateAvailable", "isFirstUseDateAvailable")) {
                        hook(m).intercept(hookReturnTrue)
                    }
                }
            }
            log(Log.INFO, TAG, "Added Battery info entry")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to add Battery info entry: $t")
        }

        try {
            val dashboardFragment = lpparam.defaultClassLoader.loadClass("com.android.settings.dashboard.DashboardFragment")
            val onViewCreated = dashboardFragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
            
            hook(onViewCreated).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val fragment = chain.thisObject
                    if (fragment != null && fragment.javaClass.name.contains("BatteryInfoFragment")) {
                        try {
                            val context = fragment.javaClass.getMethod("getContext").invoke(fragment) as? Context
                            val screen = fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment)
                            if (context != null && screen != null) {
                                val findPreference = screen.javaClass.getMethod("findPreference", CharSequence::class.java)
                                if (findPreference.invoke(screen, "battery_info_serial_number") == null) {
                                    val batterySerial = getBatterySerialRoot()
                                    if (batterySerial.isNotEmpty()) {
                                        addPrefSettings(context, screen, "battery_info_serial_number", "Serial number", "Tap to show info", "battery_info_first_use_date") {
                                            batterySerial
                                        }
                                    } else {
                                        log(Log.ERROR, TAG, "Failed to get serial number")
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "Settings injection failed: $t")
                        }
                    }
                    return result
                }
            })
            log(Log.INFO, TAG, "Added serial number")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to add serial number: $t")
        }
    }

    private fun addPrefSettings(
        context: Context,
        screen: Any,
        key: String,
        title: String,
        summary: String,
        afterKey: String,
        clickAction: () -> String
    ) {
        try {
            val prefClass = context.classLoader.loadClass("androidx.preference.Preference")
            val pref = prefClass.getConstructor(Context::class.java).newInstance(context)

            prefClass.getMethod("setKey", String::class.java).invoke(pref, key)
            prefClass.getMethod("setTitle", CharSequence::class.java).invoke(pref, title)
            prefClass.getMethod("setSummary", CharSequence::class.java).invoke(pref, summary)
            
            try {
                prefClass.getMethod("setCopyingEnabled", Boolean::class.javaPrimitiveType).invoke(pref, true)
            } catch (ignored: Exception) {}

            val storedSerial = clickAction()
            val listenerClass = context.classLoader.loadClass("androidx.preference.Preference\$OnPreferenceClickListener")
            val proxyListener = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(listenerClass),
                object : InvocationHandler {
                    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                        return if (method.name == "onPreferenceClick") {
                            prefClass.getMethod("setSummary", CharSequence::class.java).invoke(pref, storedSerial)
                            true
                        } else null
                    }
                }
            )
            prefClass.getMethod("setOnPreferenceClickListener", listenerClass).invoke(pref, proxyListener)

            val findPreference = screen.javaClass.getMethod("findPreference", CharSequence::class.java)
            val anchor = findPreference.invoke(screen, afterKey)
            if (anchor != null) {
                val order = anchor.javaClass.getMethod("getOrder").invoke(anchor) as Int
                prefClass.getMethod("setOrder", Int::class.javaPrimitiveType).invoke(pref, order + 1)
            }
            screen.javaClass.getMethod("addPreference", prefClass).invoke(screen, pref)
            log(Log.INFO, TAG, "Added $title pref")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to add $title pref : $t")
        }
    }

    private fun findClassesIntelligence(lpparam: XposedModuleInterface.PackageLoadedParam): Boolean {
        log(Log.INFO, TAG, "Starting dynamic class search...")
        try {
            val dexFile = DexFile(lpparam.applicationInfo.sourceDir)
            var entries: Enumeration<String> = dexFile.entries()

            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (className.contains(".") && !className.startsWith("defpackage.")) continue
                try {
                    val clazz = lpparam.defaultClassLoader.loadClass(className)
                    if (clazz.isEnum) {
                        val s = clazz.enumConstants?.contentToString() ?: ""
                        if (s.contains("NORMAL") && s.contains("CAPACITY_REDUCED")) {
                            batteryHealthStatus = clazz
                            log(Log.INFO, TAG, "Found BatteryHealthStatus class ->  $className")
                            break
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }

            entries = dexFile.entries()
            var logInterface: Class<*>? = null
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (className.contains(".") && !className.startsWith("defpackage.")) continue
                try {
                    val clazz = lpparam.defaultClassLoader.loadClass(className)

                    if (batteryHealthData == null && batteryHealthStatus != null && !clazz.isEnum && !clazz.isInterface) {
                        val f = clazz.declaredFields
                        if (f.size == 4 && f[0].type == Int::class.javaPrimitiveType && f[1].type == Int::class.javaPrimitiveType
                            && f[2].type == Int::class.javaPrimitiveType && f[3].type == batteryHealthStatus
                        ) {
                            batteryHealthData = clazz
                            log(Log.INFO, TAG, "Found BatteryHealthData class -> $className")
                        }
                    }

                    if (logInterface == null && clazz.isInterface) {
                        val methods = clazz.declaredMethods
                        if (methods.size == 10) {
                            var boolCount = 0
                            var longCount = 0
                            for (m in methods) {
                                if (m.returnType == Boolean::class.javaPrimitiveType) boolCount++
                                else if (m.returnType == Long::class.javaPrimitiveType) longCount++
                            }
                            if (boolCount == 9 && longCount == 1) {
                                logInterface = clazz
                                log(Log.INFO, TAG, "Found logInterface -> $className")
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }

            entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (className.contains(".") && !className.startsWith("defpackage.")) continue
                try {
                    val clazz = lpparam.defaultClassLoader.loadClass(className)

                    if (batteryFeatureFlags == null && logInterface != null && logInterface.isAssignableFrom(clazz) && !clazz.isInterface) {
                        batteryFeatureFlags = clazz
                        log(Log.INFO, TAG, "Found BatteryFeatureFlags class -> $className")
                    }

                    if (batteryHealthUtils == null && batteryHealthData != null) {
                        for (m in clazz.declaredMethods) {
                            val p = m.parameterTypes
                            if (Modifier.isStatic(m.modifiers) && p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == batteryHealthData) {
                                batteryHealthUtils = clazz
                                log(Log.INFO, TAG, "Found BatteryHealthUtils class -> $className")
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Error: ${e.message}")
        }
        return batteryHealthStatus != null && batteryHealthData != null && batteryFeatureFlags != null
    }

    private fun hookIntelligence() {
        batteryFeatureFlags?.declaredMethods?.forEach { m ->
            if (m.returnType == Boolean::class.javaPrimitiveType && Modifier.isPublic(m.modifiers)) {
                hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any {
                        return true
                    }
                })
            }
        }

        batteryHealthData?.declaredConstructors?.forEach { c ->
            hook(c).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val statusNormal = batteryHealthStatus?.getDeclaredField("b")?.let {
                        it.isAccessible = true
                        it.get(null)
                    }
                    val statusReduced = batteryHealthStatus?.getDeclaredField("c")?.let {
                        it.isAccessible = true
                        it.get(null)
                    }

                    val capacity = calculateCapacity()
                    val performance = calculatePerformance()
                    val health = calculateHealthIndex(capacity, performance)

                    val args = chain.args.toTypedArray()
                    if (args.size >= 4) {
                        args[0] = capacity
                        args[1] = performance
                        args[2] = health
                        if (capacity < 80 || performance < 80 || health < 80) {
                            args[3] = statusReduced
                            reduced = true
                        } else {
                            args[3] = statusNormal
                            reduced = false
                        }
                    }
                    log(Log.INFO, TAG, "Successfully injected battery health info! Max. capacity = ${args[0]}, performance index = ${args[1]}, health index = ${args[2]}")
                    return chain.proceed(args)
                }
            })
        }

        batteryHealthUtils?.declaredMethods?.forEach { m ->
            if (m.returnType == batteryHealthStatus && m.parameterTypes.size == 1) {
                hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val fieldName = if (reduced) "c" else "b"
                        return batteryHealthStatus?.getDeclaredField(fieldName)?.let {
                            it.isAccessible = true
                            it.get(null)
                        } ?: chain.proceed()
                    }
                })
            }
        }
    }

    private fun getBatterySerialRoot(): String {
        return runRootCommand("cat /sys/class/power_supply/battery/serial_number").trim()
    }

    private fun calculateCapacity(): Int {
        val path = "/sys/class/power_supply/battery/"
        val cf = readLongRoot(path + "charge_full")
        val cd = readLongRoot(path + "charge_full_design")
        return if (cf > 0 && cd > 0) ((cf * 100) / cd).toInt() else 100
    }

    private fun calculatePerformance(): Int {
        val res = (readLongRoot("/sys/class/power_supply/battery/resistance_avg") / 1000).toInt()

        return when {
            res <= 100 -> 100
            res <= 250 -> 100 - (res - 100) * 15 / 150
            res < 500 -> 85 - (res - 250) * 85 / 250
            else -> 0
        }
    }

    private fun calculateHealthIndex(capacity: Int, performance: Int): Int {
        return (capacity + performance) / 2
    }

    private fun readLongRoot(file: String): Long {
        return try {
            runRootCommand("cat $file").trim().toLong()
        } catch (e: Exception) {
            -1L
        }
    }

    private fun runRootCommand(cmd: String): String {
        val sb = StringBuilder()
        try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            val isReader = BufferedReader(InputStreamReader(p.inputStream))
            os.writeBytes("$cmd\nexit\n")
            os.flush()
            var line: String?
            while (isReader.readLine().also { line = it } != null) sb.append(line)
            p.waitFor()
        } catch (ignored: Exception) {
            log(Log.ERROR, TAG, "Root not granted! Can't get real device info.")
        }
        return sb.toString()
    }
}
