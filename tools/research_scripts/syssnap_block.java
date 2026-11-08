// ───────────── system_server 侧壁纸快照（只有它能看见 /data/system） ─────────────
    private static boolean sSysSnapInstalled;

    private static Context sysContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentActivityThread").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(cur);
        } catch (Throwable t) {
            log("拿 system context 失败: " + t);
            return null;
        }
    }

    private static void installSystemSnap() {
        if (sSysSnapInstalled) {
            return;
        }
        Context ctx = sysContext();
        if (ctx == null) {
            return;
        }
        sSysSnapInstalled = true;
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        snapAsSystem(c);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            }, new IntentFilter("com.qihang.aod.SNAP"), Context.RECEIVER_EXPORTED);
            log("system_server 壁纸快照接收器已注册");
        } catch (Throwable t) {
            log("注册 system_server 快照接收器失败: " + t);
        }
    }

    private static void snapAsSystem(Context ctx) {
        byte[] data = null;
        try (java.io.InputStream in = new java.io.FileInputStream(SNAP_SRC)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            data = bos.toByteArray();
        } catch (Throwable t) {
            log("system_server 读壁纸失败: " + t);
        }
        if (data == null || data.length < 1024) {
            log("system_server 读壁纸过短: " + (data == null ? -1 : data.length));
            return;
        }
        log("system_server 读到壁纸 " + data.length + "B");
        String[] targets = {
                "/sdcard/Android/data/com.qihang.aod/files/wp_snapshot.bin",
                "/data/local/tmp/qihang_wp.bin",
                "/data/data/com.qihang.aod/files/wp_snapshot.bin",
        };
        boolean wrote = false;
        for (String t : targets) {
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(t)) {
                os.write(data);
                log("壁纸快照已写 " + t);
                wrote = true;
                break;
            } catch (Throwable e) {
                log("壁纸快照写失败 " + t + " : " + e);
            }
        }
        if (!wrote) {
            // 兜底：分块广播（Binder 上限 1MB，每块 640KB）
            int chunk = 640 * 1024;
            int total = (data.length + chunk - 1) / chunk;
            for (int i = 0; i < total; i++) {
                int from = i * chunk;
                int to = Math.min(data.length, from + chunk);
                Intent it = new Intent("com.qihang.aod.SNAP_DATA");
                it.setPackage("com.qihang.aod");
                it.putExtra("idx", i);
                it.putExtra("total", total);
                it.putExtra("len", data.length);
                it.putExtra("chunk", java.util.Arrays.copyOfRange(data, from, to));
                ctx.sendBroadcast(it);
            }
            log("壁纸快照改走分块广播: " + total + " 块");
        }
    }