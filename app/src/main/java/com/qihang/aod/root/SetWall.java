package com.qihang.aod.root;

import android.content.ComponentName;
import android.content.Context;
import java.lang.reflect.Method;

/**
 * 设置壁纸（桌面/锁屏/单槽位一体化 which=3）。
 */
public final class SetWall {

    private SetWall() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 3) {
                System.out.println("SETWALL usage: <pkg> <serviceClass> <which> | <pkg> <homeClass> <lockClass> dual | clear <pkg> <which>");
                return;
            }
            if ("clear".equals(args[0])) {
                int w = Integer.parseInt(args[2]);
                System.out.println("SETWALL " + clear(args[1], w));
                return;
            }
            if (args.length >= 4 && "dual".equals(args[3])) {
                // 先将 which=3 整体替换为模块锁屏壁纸，彻底卸下可能处于 mWhich=3 的系统 ImageWallpaper，
                // 防止直接设置 which=1 时触发 MIUI 将 ImageWallpaper 自动拆迁为锁屏画报 (MiuiKeyguardPictorialWallpaper)
                String r3 = set(args[0], args[2], 3);
                String r1 = set(args[0], args[1], 1);
                String r2 = set(args[0], args[2], 2);
                System.out.println("SETWALL " + r3 + " ; " + r1 + " ; " + r2);
                return;
            }
            int which = Integer.parseInt(args[2]);
            System.out.println("SETWALL " + set(args[0], args[1], which));
        } catch (Throwable t) {
            System.out.println("SETWALL ERR " + t);
        }
    }

    public static String clear(String pkg, int which) {
        try {
            Object iwm = wallpaperService();
            Method target = null;
            for (Method m : iwm.getClass().getMethods()) {
                if ("clearWallpaper".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                return "ERR no clearWallpaper";
            }
            target.setAccessible(true);
            int params = target.getParameterCount();
            if (params == 3) {
                target.invoke(iwm, pkg, which, 0);
            } else if (params == 2) {
                target.invoke(iwm, which, 0);
            } else {
                return "ERR unknown clearWallpaper param count " + params;
            }
            return "OK clear which=" + which;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            c.printStackTrace();
            return "ERR " + c;
        }
    }

    public static String set(String pkg, String serviceClass, int which) {
        try {
            Class<?> componentName = Class.forName("android.content.ComponentName");
            Object cn = componentName.getConstructor(String.class, String.class)
                    .newInstance(pkg, serviceClass);

            Class<?> descClass = Class.forName("android.app.wallpaper.WallpaperDescription");
            Class<?> builderClass = Class.forName("android.app.wallpaper.WallpaperDescription$Builder");
            Object builder = builderClass.getDeclaredConstructor().newInstance();
            builderClass.getMethod("setComponent", componentName).invoke(builder, cn);
            Object desc = builderClass.getMethod("build").invoke(builder);

            Object iwm = wallpaperService();
            Method target = null;
            for (Method m : iwm.getClass().getMethods()) {
                if ("setWallpaperComponentChecked".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                return "ERR no setWallpaperComponentChecked";
            }
            target.setAccessible(true);
            int params = target.getParameterCount();
            if (params == 4) {
                target.invoke(iwm, desc, pkg, which, 0);
            } else if (params == 5) {
                target.invoke(iwm, desc, pkg, which, 0, null);
            } else {
                return "ERR unknown param count " + params;
            }
            return "OK which=" + which + " cn=" + pkg + "/" + serviceClass;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            c.printStackTrace();
            return "ERR " + c;
        }
    }

    private static Object wallpaperService() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Object b = sm.getMethod("getService", String.class).invoke(null, "wallpaper");
        Class<?> stub = Class.forName("android.app.IWallpaperManager$Stub");
        return stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
    }

    public static String setSelf(Context ctx, int which) {
        return set(ctx.getPackageName(), ctx.getPackageName() + ".QihangWallpaper", which);
    }

    public static String componentName(Context ctx) {
        return new ComponentName(ctx.getPackageName(), ctx.getPackageName() + ".QihangWallpaper").flattenToString();
    }
}
