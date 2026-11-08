package com.qihang.aod.root;

import android.content.ComponentName;
import android.content.Context;

import java.lang.reflect.Method;

/**
 * 设置壁纸（桌面/锁屏/两者）。
 *
 * <p>两条通路：
 * <ol>
 *   <li>root：{@code app_process} 直接调 hidden 的
 *       {@code IWallpaperManager.setWallpaperComponentChecked(desc, pkg, which, userId)}，不受 hidden-api 限制；</li>
 *   <li>进程内：同样反射调用，但受 hidden API 灰名单影响，作为兜底。</li>
 * </ol>
 * {@code which}: 1=桌面，2=锁屏，3=两者。注意 **锁屏槽位（2）才是息屏时显示的那张**。
 */
public final class SetWall {

    private SetWall() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 3) {
                System.out.println("SETWALL usage: <pkg> <serviceClass> <which> | <pkg> <homeClass> <lockClass> dual");
                return;
            }
            if (args.length >= 4 && "dual".equals(args[3])) {
                String r1 = set(args[0], args[1], 1);
                String r2 = set(args[0], args[2], 2);
                System.out.println("SETWALL " + r1 + " ; " + r2);
                return;
            }
            System.out.println("SETWALL " + set(args[0], args[1], Integer.parseInt(args[2])));
        } catch (Throwable t) {
            System.out.println("SETWALL ERR " + t);
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
            Class<?>[] p = target.getParameterTypes();
            if (p.length == 4 && p[0] == descClass) {
                target.invoke(iwm, desc, pkg, which, 0);
            } else if (p.length == 4) {
                target.invoke(iwm, desc, pkg, which, 0);
            } else {
                return "ERR unexpected arity " + p.length;
            }
            return "ok which=" + which + " -> " + pkg + "/" + serviceClass;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "ERR " + cause;
        }
    }

    private static Object wallpaperService() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Object binder = serviceManager.getMethod("getService", String.class).invoke(null, "wallpaper");
        Class<?> stub = Class.forName("android.app.IWallpaperManager$Stub");
        Class<?> ibinder = Class.forName("android.os.IBinder");
        return stub.getMethod("asInterface", ibinder).invoke(null, binder);
    }

    /** 便捷入口：设本模块自己的壁纸。 */
    public static String setSelf(Context ctx, int which) {
        return set(ctx.getPackageName(), ctx.getPackageName() + ".QihangWallpaper", which);
    }

    public static String componentName(Context ctx) {
        return new ComponentName(ctx.getPackageName(), ctx.getPackageName() + ".QihangWallpaper").flattenToString();
    }
}