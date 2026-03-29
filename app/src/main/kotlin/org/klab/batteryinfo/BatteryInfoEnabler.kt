package org.klab.batteryinfo

import android.content.Context
import android.os.Bundle
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Enumeration
import dalvik.system.DexFile

class BatteryInfoEnabler : IXposedHookLoadPackage {

    private var batteryHealthData: Class<*>? = null
    private var batteryHealthStatus: Class<*>? = null
    private var batteryFeatureFlags: Class<*>? = null
    private var batteryHealthUtils: Class<*>? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.android.settings") {
            hookSettings(lpparam)
        }
        if (lpparam.packageName != "com.google.android.settings.intelligence") return

        if (findClassesIntelligence(lpparam)) {
            XposedBridge.log("Successfully found classes for Settings Services")
            hookIntelligence()
        } else {
            XposedBridge.log("Failed to find classes for Settings Services")
        }
    }

    private fun hookSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val googleProvider = XposedHelpers.findClass(
                "com.google.android.settings.fuelgauge.BatterySettingsFeatureProviderGoogleImpl",
                lpparam.classLoader
            )
            findAndHookMethod(googleProvider, "isBatteryInfoEnabled", Context::class.java, XC_MethodReplacement.returnConstant(true))
            findAndHookMethod(googleProvider, "isManufactureDateAvailable", Context::class.java, Long::class.javaPrimitiveType, XC_MethodReplacement.returnConstant(true))
            findAndHookMethod(googleProvider, "isFirstUseDateAvailable", Context::class.java, Long::class.javaPrimitiveType, XC_MethodReplacement.returnConstant(true))
            XposedBridge.log("Added Battery info entry")
        } catch (t: Throwable) {
            XposedBridge.log("Settings hook failed: $t")
        }

        try {
            findAndHookMethod(
                "com.android.settings.dashboard.DashboardFragment",
                lpparam.classLoader,
                "onViewCreated",
                View::class.java,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fragment = param.thisObject
                        if (!fragment.javaClass.name.contains("BatteryInfoFragment")) return

                        val context = XposedHelpers.callMethod(fragment, "getContext") as? Context ?: return
                        val screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: return

                        if (XposedHelpers.callMethod(screen, "findPreference", "battery_info_serial_number") == null) {
                            val batterySerial = getBatterySerialRoot()
                            if (batterySerial.isNotEmpty()) {
                                addPrefSettings(context, screen, "battery_info_serial_number", "Serial number", "Tap to show info", "battery_info_first_use_date") {
                                    batterySerial
                                }
                            } else {
                                XposedBridge.log("Failed to get serial number")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("Settings injection failed: $t")
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
            val prefClass = XposedHelpers.findClass("androidx.preference.Preference", context.classLoader)
            val pref = XposedHelpers.newInstance(prefClass, context)

            XposedHelpers.callMethod(pref, "setKey", key)
            XposedHelpers.callMethod(pref, "setTitle", title)
            XposedHelpers.callMethod(pref, "setSummary", summary)
            XposedHelpers.callMethod(pref, "setCopyingEnabled", true)

            val storedSerial = clickAction()
            val listenerClass = XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceClickListener", context.classLoader)
            val proxyListener = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(listenerClass),
                object : InvocationHandler {
                    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                        return if (method.name == "onPreferenceClick") {
                            XposedHelpers.callMethod(pref, "setSummary", storedSerial)
                            true
                        } else null
                    }
                }
            )
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", proxyListener)

            val anchor = XposedHelpers.callMethod(screen, "findPreference", afterKey)
            if (anchor != null) {
                val order = XposedHelpers.callMethod(anchor, "getOrder") as Int
                XposedHelpers.callMethod(pref, "setOrder", order + 1)
            }
            XposedHelpers.callMethod(screen, "addPreference", pref)
            XposedBridge.log("Added $title pref")
        } catch (t: Throwable) {
            XposedBridge.log("Failed to add $title pref : $t")
        }
    }

    private fun findClassesIntelligence(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        XposedBridge.log("Starting dynamic class search...")
        try {
            val dexFile = DexFile(lpparam.appInfo.sourceDir)
            var entries: Enumeration<String> = dexFile.entries()

            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (className.contains(".") && !className.startsWith("defpackage.")) continue
                try {
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                    if (clazz.isEnum) {
                        val s = clazz.enumConstants?.contentToString() ?: ""
                        if (s.contains("NORMAL") && s.contains("CAPACITY_REDUCED")) {
                            batteryHealthStatus = clazz
                            XposedBridge.log("Found BatteryHealthStatus class ->  $className")
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
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                    if (batteryHealthData == null && batteryHealthStatus != null && !clazz.isEnum && !clazz.isInterface) {
                        val f = clazz.declaredFields
                        if (f.size == 4 && f[0].type == Int::class.javaPrimitiveType && f[1].type == Int::class.javaPrimitiveType
                            && f[2].type == Int::class.javaPrimitiveType && f[3].type == batteryHealthStatus
                        ) {
                            batteryHealthData = clazz
                            XposedBridge.log("Found BatteryHealthData class -> $className")
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
                                XposedBridge.log("Found logInterface -> $className")
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
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                    if (batteryFeatureFlags == null && logInterface != null && logInterface.isAssignableFrom(clazz) && !clazz.isInterface) {
                        batteryFeatureFlags = clazz
                        XposedBridge.log("Found BatteryFeatureFlags class -> $className")
                    }

                    if (batteryHealthUtils == null && batteryHealthData != null) {
                        for (m in clazz.declaredMethods) {
                            val p = m.parameterTypes
                            if (Modifier.isStatic(m.modifiers) && p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == batteryHealthData) {
                                batteryHealthUtils = clazz
                                XposedBridge.log("Found BatteryHealthUtils class -> $className")
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("Error: ${e.message}")
        }
        return batteryHealthStatus != null && batteryHealthData != null && batteryFeatureFlags != null
    }

    private val reduced = booleanArrayOf(false)

    private fun hookIntelligence() {
        batteryFeatureFlags?.declaredMethods?.forEach { m ->
            if (m.returnType == Boolean::class.javaPrimitiveType && Modifier.isPublic(m.modifiers)) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })
            }
        }

        XposedBridge.hookAllConstructors(batteryHealthData, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val statusNormal = XposedHelpers.getStaticObjectField(batteryHealthStatus, "b")
                val statusReduced = XposedHelpers.getStaticObjectField(batteryHealthStatus, "c")

                val capacity = calculateCapacity()
                val performance = calculatePerformance()
                val health = calculateHealthIndex(capacity, performance)

                param.args[0] = if (capacity > 0) capacity else 100
                param.args[1] = performance
                param.args[2] = health
                if (capacity < 80 || performance < 80 || health < 80) {
                    param.args[3] = statusReduced
                    reduced[0] = true
                } else {
                    param.args[3] = statusNormal
                }
                XposedBridge.log("Successfully injected battery health info! Max. capacity = $capacity, performance index = $performance, health index = $health")
            }
        })

        batteryHealthUtils?.declaredMethods?.forEach { m ->
            if (m.returnType == batteryHealthStatus && m.parameterTypes.size == 1) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val status = if (reduced[0]) "c" else "b"
                        param.result = XposedHelpers.getStaticObjectField(batteryHealthStatus, status)
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
        return if (cf > 0 && cd > 0) ((cf * 100) / cd).toInt() else -1
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
            val `is` = BufferedReader(InputStreamReader(p.inputStream))
            os.writeBytes("$cmd\nexit\n")
            os.flush()
            var line: String?
            while (`is`.readLine().also { line = it } != null) sb.append(line)
            p.waitFor()
        } catch (ignored: Exception) {
        }
        return sb.toString()
    }
}
