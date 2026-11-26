package io.github.askt_clot.hyperalive;

import android.content.Context;
import android.os.Build;

/**
 * 统一 Device Protected Storage 上下文辅助：
 * 在 Android N (API 24) 及以上优先转换为设备加密存储上下文，确保 Direct Boot 解锁前后均可读写配置与缓存；
 * 若底层运行环境或测试桩不支持，则安全回退至原始 Context。
 */
final class DirectBootContext {

    static volatile Boolean sForceDeForTest = null;

    private DirectBootContext() {
    }

    static Context get(Context context) {
        if (context == null) {
            return null;
        }
        Boolean forced = sForceDeForTest;
        boolean useDe = forced != null ? forced.booleanValue() : (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        if (useDe) {
            try {
                if (!context.isDeviceProtectedStorage()) {
                    Context de = context.createDeviceProtectedStorageContext();
                    if (de != null) {
                        return de;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return context;
    }
}
