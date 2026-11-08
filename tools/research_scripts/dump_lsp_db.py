import deploy

dump_script = """
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;

public class FullDumpLsp {
    public static void main(String[] args) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase("/data/adb/lspd/config/modules_config.db", null, SQLiteDatabase.OPEN_READONLY);
        System.out.println("--- MODULES ---");
        Cursor c1 = db.rawQuery("SELECT * FROM modules", null);
        while (c1.moveToNext()) {
            System.out.println("  pkg=" + c1.getString(0) + " path=" + c1.getString(1));
        }
        c1.close();
        System.out.println("--- MODULES_STATE ---");
        Cursor c2 = db.rawQuery("SELECT * FROM modules_state", null);
        while (c2.moveToNext()) {
            System.out.println("  pkg=" + c2.getString(0) + " enabled=" + c2.getInt(1));
        }
        c2.close();
        System.out.println("--- SCOPE FOR com.qihang.aod ---");
        Cursor c3 = db.rawQuery("SELECT * FROM scope WHERE module_pkg_name = 'com.qihang.aod'", null);
        while (c3.moveToNext()) {
            System.out.println("  scope: " + c3.getString(1));
        }
        c3.close();
        db.close();
    }
}
"""

with open('FullDumpLsp.java', 'w') as f:
    f.write(dump_script)

deploy.push_dex() # compile or run
out = deploy.app_process('FullDumpLsp')
print(out)
