package com.swift.sandhook.xposedcompat;

import android.app.ActivityThread;
import android.app.Application;
import android.app.LoadedApk;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.ResourcesImpl;
import android.content.res.TypedArray;
import android.content.res.XResources;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import com.swift.sandhook.SandHookConfig;
import com.swift.sandhook.xposedcompat.classloaders.ProxyClassLoader;
import com.swift.sandhook.xposedcompat.methodgen.DynamicBridge;
import com.swift.sandhook.xposedcompat.utils.ApplicationUtils;
import com.swift.sandhook.xposedcompat.utils.FileUtils;
import com.swift.sandhook.xposedcompat.utils.ProcessUtils;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XCallback;

import static com.swift.sandhook.xposedcompat.utils.DexMakerUtils.MD5;
import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getParameterIndexByType;
import static de.robv.android.xposed.XposedHelpers.setStaticObjectField;

public class XposedCompat {

    public static File cacheDir;
    public static Context context;
    public static volatile ClassLoader classLoader;
    public static String packageName;
    public static String processName;
    public static boolean isFirstApplication;

    //try to use internal stub hooker & backup method to speed up hook
    public static volatile boolean useInternalStub = true;
    public static volatile boolean useNewCallBackup = true;
    public static volatile boolean retryWhenCallOriginError = false;

    private static ClassLoader sandHookXposedClassLoader;
    private static ClassLoader dummyClassLoader;


    public static void loadModule(String modulePath, String moduleOdexDir, String moduleSoPath, ClassLoader classLoader) {
        XposedInit.loadModule(modulePath, moduleOdexDir, moduleSoPath, classLoader);
    }

    public static XResources[] hookResources() throws Exception {
        if (!initXResourcesNative(XResources.class)) return new XResources[0];

        findAndHookMethod("android.app.ApplicationPackageManager", null, "getResourcesForApplication",
                ApplicationInfo.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        ApplicationInfo app = (ApplicationInfo) param.args[0];
                        XResources.setPackageNameForResDir(app.packageName,
                                app.uid == Process.myUid() ? app.sourceDir : app.publicSourceDir);
                    }
                });

        /*
         * getTopLevelResources(a)
         *   -> getTopLevelResources(b)
         *     -> key = new ResourcesKey()
         *     -> r = new Resources()
         *     -> mActiveResources.put(key, r)
         *     -> return r
         */

        final Class<?> classGTLR;
        final Class<?> classResKey;
        final ThreadLocal<Object> latestResKey = new ThreadLocal<>();

        if (Build.VERSION.SDK_INT <= 18) {
            classGTLR = ActivityThread.class;
            classResKey = Class.forName("android.app.ActivityThread$ResourcesKey");
        } else {
            classGTLR = Class.forName("android.app.ResourcesManager");
            classResKey = Class.forName("android.content.res.ResourcesKey");
        }

        if (Build.VERSION.SDK_INT >= 24) {
            hookAllMethods(classGTLR, "getOrCreateResources", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // At least on OnePlus 5, the method has an additional parameter compared to AOSP.
                    final int activityTokenIdx = getParameterIndexByType(param.method, IBinder.class);
                    final int resKeyIdx = getParameterIndexByType(param.method, classResKey);

                    String resDir = (String) getObjectField(param.args[resKeyIdx], "mResDir");
                    XResources newRes = cloneToXResources(param, resDir);
                    if (newRes == null) {
                        return;
                    }

                    Object activityToken = param.args[activityTokenIdx];
                    synchronized (param.thisObject) {
                        ArrayList<WeakReference<Resources>> resourceReferences;
                        if (activityToken != null) {
                            Object activityResources = callMethod(param.thisObject, "getOrCreateActivityResourcesStructLocked", activityToken);
                            resourceReferences = (ArrayList<WeakReference<Resources>>) getObjectField(activityResources, "activityResources");
                        } else {
                            resourceReferences = (ArrayList<WeakReference<Resources>>) getObjectField(param.thisObject, "mResourceReferences");
                        }
                        resourceReferences.add(new WeakReference(newRes));
                    }
                }
            });
        } else {
            hookAllConstructors(classResKey, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    latestResKey.set(param.thisObject);
                }
            });

            hookAllMethods(classGTLR, "getTopLevelResources", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    latestResKey.set(null);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object key = latestResKey.get();
                    if (key == null) {
                        return;
                    }
                    latestResKey.set(null);

                    String resDir = (String) getObjectField(key, "mResDir");
                    XResources newRes = cloneToXResources(param, resDir);
                    if (newRes == null) {
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    Map<Object, WeakReference<Resources>> mActiveResources =
                            (Map<Object, WeakReference<Resources>>) getObjectField(param.thisObject, "mActiveResources");
                    Object lockObject = (Build.VERSION.SDK_INT <= 18)
                            ? getObjectField(param.thisObject, "mPackages") : param.thisObject;

                    synchronized (lockObject) {
                        WeakReference<Resources> existing = mActiveResources.put(key, new WeakReference<Resources>(newRes));
                        if (existing != null && existing.get() != null && existing.get().getAssets() != newRes.getAssets()) {
                            existing.get().getAssets().close();
                        }
                    }
                }
            });

            if (Build.VERSION.SDK_INT >= 19) {
                // This method exists only on CM-based ROMs
                hookAllMethods(classGTLR, "getTopLevelThemedResources", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String resDir = (String) param.args[0];
                        cloneToXResources(param, resDir);
                    }
                });
            }
        }
        findAndHookMethod(TypedArray.class, "obtain", Resources.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.getResult() instanceof XResources.XTypedArray) {
                            return;
                        }
                        if (!(param.args[0] instanceof XResources)) {
                            return;
                        }
                        XResources.XTypedArray newResult =
                                new XResources.XTypedArray((Resources) param.args[0]);
                        int len = (int) param.args[1];
                        Method resizeMethod = XposedHelpers.findMethodBestMatch(
                                TypedArray.class, "resize", new Class[]{int.class});
                        resizeMethod.setAccessible(true);
                        resizeMethod.invoke(newResult, len);
                        param.setResult(newResult);
                    }
                });

        Application application = ApplicationUtils.currentApplication();
        XResources newRes = null;
        if (application != null) {
            Object contextImpl = XposedHelpers.getObjectField(application, "mBase");
            LoadedApk loadedApk = (LoadedApk) XposedHelpers.getObjectField(contextImpl, "mPackageInfo");
            String resDir = (String) XposedHelpers.getObjectField(loadedApk, "mResDir");
            Resources res = (Resources) XposedHelpers.getObjectField(contextImpl, "mResources");
            ResourcesImpl resImpl = (ResourcesImpl) XposedHelpers.getObjectField(res, "mResourcesImpl");
            ClassLoader classLoader = (ClassLoader) XposedHelpers.getObjectField(contextImpl, "mClassLoader");
            newRes = new XResources(classLoader);
            newRes.setImpl(resImpl);
            newRes.initObject(resDir);
            XposedHelpers.setObjectField(contextImpl, "mResources", newRes);
            XposedHelpers.setObjectField(loadedApk, "mResources", newRes);
        }

        // Replace system resources
        XResources systemRes = new XResources(
                (ClassLoader) XposedHelpers.getObjectField(Resources.getSystem(), "mClassLoader"));
        systemRes.setImpl((ResourcesImpl) XposedHelpers.getObjectField(Resources.getSystem(), "mResourcesImpl"));
        systemRes.initObject(null);
        setStaticObjectField(Resources.class, "mSystem", systemRes);
        XResources.init(null);
        // Return these replaced resources to further call by hookInitPackageResources
        if (newRes != null) {
            return new XResources[]{newRes, systemRes};
        } else {
            return new XResources[]{systemRes};
        }

    }

    private static XResources cloneToXResources(XC_MethodHook.MethodHookParam param, String resDir) {
        Object result = param.getResult();
        if (result == null || result instanceof XResources) {
            return null;
        }

        // Replace the returned resources with our subclass.
        XResources newRes = new XResources(
                (ClassLoader) XposedHelpers.getObjectField(param.getResult(), "mClassLoader"));
        newRes.setImpl((ResourcesImpl) XposedHelpers.getObjectField(param.getResult(), "mResourcesImpl"));
        newRes.initObject(resDir);

        if (newRes.isFirstLoad()) {
            String packageName = newRes.getPackageName();
            XC_InitPackageResources.InitPackageResourcesParam resparam = new XC_InitPackageResources.InitPackageResourcesParam(XposedBridge.sInitPackageResourcesCallbacks);
            resparam.packageName = packageName;
            resparam.res = newRes;
            XCallback.callAll(resparam);
        }

        param.setResult(newRes);
        return newRes;
    }

    private static native boolean initXResourcesNative(Class clazz);


    public static void addXposedModuleCallback(IXposedHookLoadPackage module) {
        XposedBridge.hookLoadPackage(new IXposedHookLoadPackage.Wrapper(module));
    }

    public static void addXposedModuleResourceCallback(IXposedHookInitPackageResources module) {
        XposedBridge.hookInitPackageResources(new IXposedHookInitPackageResources.Wrapper(module));
    }

    public static void callXposedModuleInit() throws Throwable {
        //prepare LoadPackageParam
        XC_LoadPackage.LoadPackageParam packageParam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
        Application application = ApplicationUtils.currentApplication();


        if (application != null) {
            if (packageParam.packageName == null) {
                packageParam.packageName = application.getPackageName();
            }

            if (packageParam.processName == null) {
                packageParam.processName = ProcessUtils.getProcessName(application);
            }
            if (packageParam.classLoader == null) {
                packageParam.classLoader = application.getClassLoader();
            }
            if (packageParam.appInfo == null) {
                packageParam.appInfo = application.getApplicationInfo();
            }

            if (cacheDir == null) {
                application.getCacheDir();
            }
        }
        XC_LoadPackage.callAll(packageParam);
    }

    public static ClassLoader getSandHookXposedClassLoader(ClassLoader appOriginClassLoader, ClassLoader sandBoxHostClassLoader) {
        if (sandHookXposedClassLoader != null) {
            return sandHookXposedClassLoader;
        } else {
            sandHookXposedClassLoader = new ProxyClassLoader(sandBoxHostClassLoader, appOriginClassLoader);
            return sandHookXposedClassLoader;
        }
    }

    public static File getCacheDir() {
        if (cacheDir == null) {
            if (context == null) {
                context = ApplicationUtils.currentApplication();
            }
            if (context != null) {
                cacheDir = new File(context.getCacheDir(), MD5(processName != null ? processName : ProcessUtils.getProcessName(context)));
            }
        }
        return cacheDir;
    }

    public static boolean clearCache() {
        try {
            FileUtils.delete(getCacheDir());
            getCacheDir().mkdirs();
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    public static void clearOatCache() {
        DynamicBridge.clearOatFile();
    }

}
