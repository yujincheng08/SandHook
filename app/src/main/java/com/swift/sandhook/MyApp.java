package com.swift.sandhook;

import android.app.Activity;
import android.app.Application;
import android.content.res.XModuleResources;
import android.content.res.XResources;
import android.os.Build;
import android.support.design.widget.FloatingActionButton;
import android.util.Log;
import android.widget.TextView;

import com.swift.sandhook.test.PendingHookTest;
import com.swift.sandhook.test.TestClass;
import com.swift.sandhook.testHookers.ActivityHooker;
import com.swift.sandhook.testHookers.CtrHook;
import com.swift.sandhook.testHookers.CustmizeHooker;
import com.swift.sandhook.testHookers.JniHooker;
import com.swift.sandhook.testHookers.LogHooker;
import com.swift.sandhook.testHookers.NewAnnotationApiHooker;
import com.swift.sandhook.testHookers.ObjectHooker;
import com.swift.sandhook.wrapper.HookErrorException;
import com.swift.sandhook.xposedcompat.XposedCompat;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LayoutInflated;

public class MyApp extends Application {

    //for test pending hook case
    public volatile static boolean initedTest = false;

    @Override
    public void onCreate() {
        super.onCreate();

        SandHookConfig.DEBUG = BuildConfig.DEBUG;

        if (Build.VERSION.SDK_INT == 29 && getPreviewSDKInt() > 0) {
            // Android R preview
            SandHookConfig.SDK_INT = 30;
        }

        SandHook.disableVMInline();
        SandHook.tryDisableProfile(getPackageName());
        SandHook.disableDex2oatInline(false);

        if (SandHookConfig.SDK_INT >= Build.VERSION_CODES.P) {
            SandHook.passApiCheck();
        }

        try {
            SandHook.addHookClass(JniHooker.class,
                    CtrHook.class,
                    LogHooker.class,
                    CustmizeHooker.class,
                    ActivityHooker.class,
                    ObjectHooker.class,
                    NewAnnotationApiHooker.class);
        } catch (HookErrorException e) {
            e.printStackTrace();
        }

        //for xposed compat(no need xposed comapt new)
        XposedCompat.cacheDir = getCacheDir();

        //for load xp module(sandvxp)
        XposedCompat.context = this;
        XposedCompat.classLoader = getClassLoader();
        XposedCompat.isFirstApplication = true;

        try {
            XposedCompat.hookResources();
        } catch (Throwable e) {
            Log.e("sandhook", e.toString());
        }

        XposedCompat.addXposedModuleResourceCallback(new IXposedHookInitPackageResources() {
            @Override
            public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable {
                resparam.res.setReplacement(R.string.app_name, "SandHookWithResourcesHook");
                resparam.res.hookLayout(R.layout.activity_main, new XC_LayoutInflated() {
                    @Override
                    public void handleLayoutInflated(LayoutInflatedParam liparam) throws Throwable {
                        FloatingActionButton button = liparam.view.findViewById(R.id.fab);
                        button.setImageResource(android.R.drawable.ic_dialog_dialer);
                    }
                });
                resparam.res.hookLayout(R.layout.content_main, new XC_LayoutInflated() {
                    @Override
                    public void handleLayoutInflated(LayoutInflatedParam liparam) throws Throwable {
                        TextView text = liparam.view.findViewById(R.id.sample_text);
                        text.setText("Hello World! by Resources Hook");
                    }
                });
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                Log.e("XposedCompat", "beforeHookedMethod: " + param.method.getName());
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                Log.e("XposedCompat", "afterHookedMethod: " + param.method.getName());
            }
        });


        XposedHelpers.findAndHookMethod(MainActivity.class, "testStub", TestClass.class, int.class, String.class, boolean.class, char.class, String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.args[1] = 2;
                Log.e("XposedCompat", "beforeHookedMethod: " + param.method.getName());
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                Log.e("XposedCompat", "afterHookedMethod: " + param.method.getName());
            }
        });

        XposedHelpers.findAndHookMethod(PendingHookTest.class, "test", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.returnEarly = true;
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
            }
        });

        XposedBridge.hookAllConstructors(Thread.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
            }
        });
    }

    public static int getPreviewSDKInt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                return Build.VERSION.PREVIEW_SDK_INT;
            } catch (Throwable e) {
                // ignore
            }
        }
        return 0;
    }
}
