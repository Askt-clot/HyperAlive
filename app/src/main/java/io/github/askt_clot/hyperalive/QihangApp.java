package io.github.askt_clot.hyperalive;

import android.app.Application;

/**
 * 模块应用入口：在任意页面、服务或 Provider 消费本地照片与配置前，
 * 于后台线程触发全局事务锁保护的崩溃日志恢复（不阻塞主线程）。
 */
public final class QihangApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PhotoDraftStore.triggerStartupRecoveryAsync(this);
    }
}
