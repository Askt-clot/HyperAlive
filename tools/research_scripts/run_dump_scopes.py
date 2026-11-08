import deploy

script = """
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;

public class DumpAllScopes {
    public static void main(String[] args) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase("/data/adb/lspd/config/modules_config.db", null, SQLiteDatabase.OPEN_READONLY);
        Cursor c = db.rawQuery("SELECT m.module_pkg_name, s.enabled, sc.app_pkg_name FROM modules m " +
                               "LEFT JOIN modules_state s ON m.module_pkg_name=s.module_pkg_name " +
                               "LEFT JOIN scope sc ON m.module_pkg_name=sc.module_pkg_name " +
                               "ORDER BY m.module_pkg_name", null);
        while (c.moveToNext()) {
            System.out.println(c.getString(0) + " enabled=" + c.getInt(1) + " scope=" + c.getString(2));
        }
        c.close();
        db.close();
    }
}
"""

with open('module/src/com/qihang/aod/root/DumpAllScopes.java', 'w') as f:
    f.write("package com.qihang.aod.root;\n" + script)

print(deploy.app_process('com.qihang.aod.root.DumpAllScopes'))
