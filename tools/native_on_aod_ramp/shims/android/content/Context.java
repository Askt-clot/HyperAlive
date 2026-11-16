package android.content;

import android.content.pm.PackageManager;

public class Context {
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, String perm, android.os.Handler scheduler, int flags) {
        return null;
    }
    public PackageManager getPackageManager() { return new PackageManager(); }
}
