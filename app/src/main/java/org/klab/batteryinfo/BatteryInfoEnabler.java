package org.klab.batteryinfo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class BatteryInfoEnabler implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.android.settings")) return;

        try {
            Class<?> googleProvider = XposedHelpers.findClass(
                    "com.google.android.settings.fuelgauge.BatterySettingsFeatureProviderGoogleImpl",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(googleProvider, "isBatteryInfoEnabled", Context.class, XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(googleProvider, "isManufactureDateAvailable", Context.class, long.class, XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(googleProvider, "isFirstUseDateAvailable", Context.class, long.class, XC_MethodReplacement.returnConstant(true));
            XposedBridge.log("Added Battery info entry");
        } catch (Throwable t) {
            XposedBridge.log("Hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.dashboard.DashboardFragment",
                    lpparam.classLoader,
                    "onViewCreated",
                    android.view.View.class,
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object fragment = param.thisObject;
                            if (!fragment.getClass().getName().contains("BatteryInfoFragment")) return;

                            final Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
                            final Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");

                            if (screen == null || context == null) return;

                            if (XposedHelpers.callMethod(screen, "findPreference", "battery_info_maximum_capacity") == null) {
                                int capacity = calculateCapacityRoot();
                                if (capacity > 0) {
                                    addPref(context, screen, "battery_info_maximum_capacity", "Maximum capacity", capacity + "%", "battery_info_first_use_date", null);
                                }
                                else {
                                    XposedBridge.log("Failed to get capacity info");
                                }
                            }

                            if (XposedHelpers.callMethod(screen, "findPreference", "battery_info_serial_number") == null) {
                                final String batterySerial = getBatterySerialRoot();
                                if (batterySerial != null && !batterySerial.isEmpty()) {
                                    addPref(context, screen, "battery_info_serial_number", "Serial number", "Tap to show info", "battery_info_maximum_capacity", new Runnable() {
                                        @Override public void run() {}
                                        @Override public String toString() { return batterySerial; }
                                    });
                                }
                                else {
                                    XposedBridge.log("Failed to get serial number");
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("Injection failed: " + t);
        }
    }

    private void addPref(final Context context, Object screen, String key, String title, String summary, String afterKey, final Runnable clickAction) {
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
    private int calculateCapacityRoot() {
        String path = "/sys/class/power_supply/battery/";
        long cf = readLongRoot(path + "charge_full");
        long cd = readLongRoot(path + "charge_full_design");
        return (cf > 0 && cd > 0) ? (int) ((cf * 100) / cd) : -1;
    }

    private String getBatterySerialRoot() {
        String serial = runRootCommand("cat /sys/class/power_supply/battery/serial_number");
        return serial.trim();
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