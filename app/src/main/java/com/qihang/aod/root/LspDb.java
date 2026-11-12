package com.qihang.aod.root;

import android.database.sqlite.SQLiteDatabase;

/**
 * 以 root 直改 LSPosed 的 {@code modules_config.db}：注册模块、打开开关、写入作用域。
 * 设备上没有 sqlite3，所以只能靠 app_process + android.database.sqlite。
 *
 * <p>用法（app_process）：
 * <pre>LspDb &lt;apkPath&gt; &lt;modulePkg&gt; [scopePkg...]</pre>
 * 默认数据库路径 {@code /data/adb/lspd/config/modules_config.db}，可用 -D 覆盖（第二个参数起以 db= 开头）。
 */
public final class LspDb {

    private static final String DEFAULT_DB = "/data/adb/lspd/config/modules_config.db";

    private LspDb() {
    }

    public static void main(String[] args) {
        try {
            if (args.length >= 2 && "-disable".equals(args[0])) {
                System.out.println("LSPDB " + disable(DEFAULT_DB, args[1]));
                return;
            }
            if (args.length >= 2 && "-get".equals(args[0])) {
                int userId = 0;
                if (args.length >= 3) {
                    try {
                        userId = Integer.parseInt(args[2]);
                    } catch (Throwable ignored) {}
                }
                System.out.println("LSPDB " + get(DEFAULT_DB, args[1], userId));
                return;
            }
            if (args.length < 2) {
                System.out.println("LSPDB usage: <apkPath> <modulePkg> [scopePkg...] | -disable <pkg> | -get <pkg>");
                return;
            }
            System.out.println("LSPDB " + sync(DEFAULT_DB, args[0], args[1],
                    java.util.Arrays.copyOfRange(args, 2, args.length)));
        } catch (Throwable t) {
            System.out.println("LSPDB ERR " + t);
        }
    }

    /**
     * 读回模块当前状态。**必须在设备侧读**：这个库是 WAL 模式，只拷主库文件（不含 -wal）
     * 在 PC 上打开会看到旧快照，会误判成"同步失败"。
     */
    public static String get(String dbPath, String modulePkg) {
        return get(dbPath, modulePkg, 0);
    }

    public static String get(String dbPath, String modulePkg, int userId) {
        SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT m.apk_path, s.enabled FROM modules m"
                    + " JOIN modules_state s ON m.module_pkg_name = s.module_pkg_name"
                    + " WHERE m.module_pkg_name = ? AND s.user_id = ?",
                    new String[]{modulePkg, String.valueOf(userId)});
            if (!c.moveToFirst()) {
                android.database.Cursor m = null;
                String path = "<none>";
                try {
                    m = db.rawQuery("SELECT apk_path FROM modules WHERE module_pkg_name = ?",
                            new String[]{modulePkg});
                    if (m.moveToFirst()) {
                        path = m.getString(0);
                    }
                } finally {
                    if (m != null) m.close();
                }
                return "get " + modulePkg + " user=" + userId + " path=" + path + " enabled=-1";
            }
            String path = c.getString(0);
            int enabled = c.getInt(1);
            StringBuilder sc = new StringBuilder();
            android.database.Cursor s = db.rawQuery(
                    "SELECT app_pkg_name FROM scope WHERE module_pkg_name = ? AND user_id = ?",
                    new String[]{modulePkg, String.valueOf(userId)});
            while (s.moveToNext()) {
                sc.append(s.getString(0)).append(',');
            }
            s.close();
            return "get " + modulePkg + " user=" + userId + " path=" + path + " enabled=" + enabled + " scope=" + sc;
        } catch (Throwable t) {
            return "get ERR " + t;
        } finally {
            if (c != null) {
                c.close();
            }
            if (db != null) {
                db.close();
            }
        }
    }

    public static String disable(String dbPath, String modulePkg) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE);
            db.execSQL("UPDATE modules_state SET enabled = 0 WHERE module_pkg_name = ?",
                    new Object[]{modulePkg});
            return "disabled " + modulePkg;
        } catch (Throwable t) {
            return "ERR " + t;
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    public static String sync(String dbPath, String apkPath, String modulePkg, String[] scope) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE);
            db.beginTransaction();
            db.execSQL("INSERT OR REPLACE INTO modules(module_pkg_name, apk_path) VALUES(?, ?)",
                    new Object[]{modulePkg, apkPath});
            db.execSQL("INSERT OR REPLACE INTO modules_state(module_pkg_name, user_id, enabled, scope_request_blocked)"
                    + " VALUES(?, 0, 1, 0)", new Object[]{modulePkg});
            db.execSQL("DELETE FROM scope WHERE module_pkg_name = ?", new Object[]{modulePkg});
            for (String s : scope) {
                if (s == null || s.trim().isEmpty()) {
                    continue;
                }
                db.execSQL("INSERT OR REPLACE INTO scope(module_pkg_name, app_pkg_name, user_id) VALUES(?, ?, 0)",
                        new Object[]{modulePkg, s.trim()});
            }
            db.setTransactionSuccessful();
            StringBuilder sb = new StringBuilder("ok apk=").append(apkPath).append(" scope=");
            for (String s : scope) {
                sb.append(s).append(',');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "ERR " + t;
        } finally {
            if (db != null) {
                try {
                    db.endTransaction();
                } catch (Throwable ignored) {
                }
                db.close();
            }
        }
    }
}