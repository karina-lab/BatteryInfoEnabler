package org.klab.batteryinfo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Enumeration;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class BatteryInfoEnabler implements IXposedHookLoadPackage {

    private Class<?> BatteryHealthData;
    private Class<?> BatteryHealthStatus;
    private Class<?> BatteryFeatureFlags;
    private Class<?> BatteryHealthUtils;
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.android.settings")) {
            hookSettings(lpparam);
        }
        if (!lpparam.packageName.equals("com.google.android.settings.intelligence")) return;

        if (findClassesIntelligence(lpparam)) {
            XposedBridge.log("Successfully found classes for Settings Services");
            hookIntelligence();
        } else {
            XposedBridge.log("Failed to find classes for Settings Services");
        }    }

    private void hookSettings(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> googleProvider = XposedHelpers.findClass(
                    "com.google.android.settings.fuelgauge.BatterySettingsFeatureProviderGoogleImpl",
                    lpparam.classLoader
            );
            findAndHookMethod(googleProvider, "isBatteryInfoEnabled", Context.class, XC_MethodReplacement.returnConstant(true));
            findAndHookMethod(googleProvider, "isManufactureDateAvailable", Context.class, long.class, XC_MethodReplacement.returnConstant(true));
            findAndHookMethod(googleProvider, "isFirstUseDateAvailable", Context.class, long.class, XC_MethodReplacement.returnConstant(true));
            XposedBridge.log("Added Battery info entry");
        } catch (Throwable t) {
            XposedBridge.log("Settings hook failed: " + t);
        }

        try {
            findAndHookMethod(
                    "com.android.settings.dashboard.DashboardFragment",
                    lpparam.classLoader,
                    "onViewCreated",
                    android.view.View.class,
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object fragment = param.thisObject;
                            if (!fragment.getClass().getName().contains("BatteryInfoFragment"))
                                return;

                            final Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
                            final Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");

                            if (screen == null || context == null) return;


                            if (XposedHelpers.callMethod(screen, "findPreference", "battery_info_serial_number") == null) {
                                final String batterySerial = getBatterySerialRoot();
                                if (batterySerial != null && !batterySerial.isEmpty()) {
                                    addPrefSettings(context, screen, "battery_info_serial_number", "Serial number", "Tap to show info", "battery_info_first_use_date", new Runnable() {
                                        @Override
                                        public void run() {
                                        }

                                        @Override
                                        public String toString() {
                                            return batterySerial;
                                        }
                                    });
                                } else {
                                    XposedBridge.log("Failed to get serial number");
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("Settings injection failed: " + t);
        }
    }
    private void addPrefSettings(final Context context, Object screen, String key, String title, String summary, String afterKey, final Runnable clickAction) {
        try {
            Class<?> prefClass = XposedHelpers.findClass("androidx.preference.Preference", context.getClassLoader());
            final Object pref = XposedHelpers.newInstance(prefClass, context);

            XposedHelpers.callMethod(pref, "setKey", key);
            XposedHelpers.callMethod(pref, "setTitle", title);
            XposedHelpers.callMethod(pref, "setSummary", summary);
            XposedHelpers.callMethod(pref, "setCopyingEnabled", true);

            if (clickAction != null) {
                final String storedSerial = clickAction.toString();
                Class<?> listenerClass = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceClickListener", context.getClassLoader());
                Object proxyListener = Proxy.newProxyInstance(context.getClassLoader(), new Class<?>[]{listenerClass}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getName().equals("onPreferenceClick")) {
                            XposedHelpers.callMethod(pref, "setSummary", storedSerial);
                            return true;
                        }
                        return null;
                    }
                });
                XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", proxyListener);
            }

            Object anchor = XposedHelpers.callMethod(screen, "findPreference", afterKey);
            if (anchor != null) {
                int order = (int) XposedHelpers.callMethod(anchor, "getOrder");
                XposedHelpers.callMethod(pref, "setOrder", order + 1);
            }
            XposedHelpers.callMethod(screen, "addPreference", pref);
            XposedBridge.log("Added " + title + " pref");
        } catch (Throwable t) {
            XposedBridge.log("Failed to add " + title + " pref : " + t);
        }
    }
    private boolean findClassesIntelligence(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("Starting dynamic class search...");
        try {
            DexFile dexFile = new DexFile(lpparam.appInfo.sourceDir);
            Enumeration<String> entries = dexFile.entries();

            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                if (className.contains(".") && !className.startsWith("defpackage.")) continue;
                try {
                    Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                    if (clazz.isEnum()) {
                        String s = java.util.Arrays.toString(clazz.getEnumConstants());
                        if (s.contains("NORMAL") && s.contains("CAPACITY_REDUCED")) {
                            BatteryHealthStatus = clazz;
                            XposedBridge.log("Found BatteryHealthStatus class ->  " + className);
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            entries = dexFile.entries();
            Class<?> logInterface = null;
            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                if (className.contains(".") && !className.startsWith("defpackage.")) continue;
                try {
                    Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);

                    if (BatteryHealthData == null && BatteryHealthStatus != null && !clazz.isEnum() && !clazz.isInterface()) {
                        Field[] f = clazz.getDeclaredFields();
                        if (f.length == 4 && f[0].getType() == int.class && f[1].getType() == int.class
                                && f[2].getType() == int.class && f[3].getType() == BatteryHealthStatus) {
                            BatteryHealthData = clazz;
                            XposedBridge.log("Found BatteryHealthData class -> " + className);
                        }
                    }

                    if (logInterface == null && clazz.isInterface()) {
                        Method[] methods = clazz.getDeclaredMethods();
                        if (methods.length == 10) {
                            int boolCount = 0, longCount = 0;
                            for (Method m : methods) {
                                if (m.getReturnType() == boolean.class) boolCount++;
                                else if (m.getReturnType() == long.class) longCount++;
                            }
                            if (boolCount == 9 && longCount == 1) {
                                logInterface = clazz;
                                XposedBridge.log("Found logInterface -> " + className);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            entries = dexFile.entries();
            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                if (className.contains(".") && !className.startsWith("defpackage.")) continue;
                try {
                    Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);

                    if (BatteryFeatureFlags == null && logInterface != null && logInterface.isAssignableFrom(clazz) && !clazz.isInterface()) {
                        BatteryFeatureFlags = clazz;
                        XposedBridge.log("Found BatteryFeatureFlags class -> " + className);
                    }

                    if (BatteryHealthUtils == null && BatteryHealthData != null) {
                        for (Method m : clazz.getDeclaredMethods()) {
                            Class<?>[] p = m.getParameterTypes();
                            if (Modifier.isStatic(m.getModifiers()) && p.length == 2 && p[0] == int.class && p[1] == BatteryHealthData) {
                                BatteryHealthUtils = clazz;
                                XposedBridge.log("Found BatteryHealthUtils class -> " + className);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Exception e) {
            XposedBridge.log("Error: " + e.getMessage());
        }
        return BatteryHealthStatus != null && BatteryHealthData != null && BatteryFeatureFlags != null;
    }
    private void hookIntelligence() {
        for (Method m : BatteryFeatureFlags.getDeclaredMethods()) {
            if (m.getReturnType() == boolean.class && Modifier.isPublic(m.getModifiers())) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(true);
                    }
                });
            }
        }

        XposedBridge.hookAllConstructors(BatteryHealthData, new XC_MethodHook() {

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {

                Object statusNormal = XposedHelpers.getStaticObjectField(BatteryHealthStatus, "b");
                Object statusReduced = XposedHelpers.getStaticObjectField(BatteryHealthStatus, "c");

                int capacity = calculateCapacity();
                int performance = calculatePerformance();
                int health = calculateHealthIndex(capacity, performance);

                param.args[0] = (capacity > 0) ? capacity : 100;
                param.args[1] = performance;
                param.args[2] = health;
                if (capacity < 80 || performance < 70 || health < 75) {
                    param.args[3] = statusReduced;
                } else {
                    param.args[3] = statusNormal;
                }
                XposedBridge.log("Successfully injected battery health info! Max. capacity = " + capacity + ", performance index = " + performance + ", health index  = " + health);
            }
        });

        if (BatteryHealthUtils != null) {
            for (Method m : BatteryHealthUtils.getDeclaredMethods()) {
                if (m.getReturnType() == BatteryHealthStatus && m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(XposedHelpers.getStaticObjectField(BatteryHealthStatus, "b"));
                        }
                    });
                }
            }
        }
    }
    private String getBatterySerialRoot() {
        String serial = runRootCommand("cat /sys/class/power_supply/battery/serial_number");
        return serial.trim();
    }
    private int calculateCapacity() {
        String path = "/sys/class/power_supply/battery/";
        long cf = readLongRoot(path + "charge_full");
        long cd = readLongRoot(path + "charge_full_design");
        return (cf > 0 && cd > 0) ? (int) ((cf * 100) / cd) : -1;
    }

    private int calculatePerformance() {
        long res = readLongRoot("/sys/class/power_supply/battery/resistance_avg");
        if (res <= 0) res = readLongRoot("/sys/class/power_supply/battery/resistance");

        if (res <= 0) return 100;

        int resMilli = (int) (res / 1000);

        if (resMilli <= 200) return 100;
        if (resMilli >= 750) return 0;

        return 100 - ((resMilli - 250) * 100 / (750 - 250));
    }
    private int calculateHealthIndex(int capacity, int performance) {
        long cycles = readLongRoot("/sys/class/power_supply/battery/cycle_count");
        int baseHealth = (capacity + performance) / 2;

        if (cycles <= 0) return baseHealth;

        int cyclePenalty = 0;
        if (cycles > 500) {
            cyclePenalty = (int) ((cycles - 500) / 100);
        }
        return Math.max(0, baseHealth - cyclePenalty);
    }
    private long readLongRoot(String file) {
        try {
            String res = runRootCommand("cat " + file);
            return Long.parseLong(res.trim());
        } catch (Exception e) { return -1; }
    }
    private String runRootCommand(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
            os.writeBytes(cmd + "\nexit\n");
            os.flush();
            String line;
            while ((line = is.readLine()) != null) sb.append(line);
            p.waitFor();
        } catch (Exception ignored) {}
        return sb.toString();
    }
}