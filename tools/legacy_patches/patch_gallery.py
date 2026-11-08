# -*- coding: utf-8 -*-
"""相册直达：优先把 ACTION_PICK 指定给系统相册包（com.miui.gallery 等），避免弹出应用选择器。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\EditorActivity.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:56].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""    /** 打开系统相册选图（ACTION_PICK 优先，退化到 OPEN_DOCUMENT）。 */
    private void pick(String name) {
        int req = PhotoStore.LOCK.equals(name) ? REQ_LOCK : REQ_HOME;
        append("打开相册（" + (req == REQ_LOCK ? "锁屏" : "桌面") + "）");
        try {
            startActivityForResult(PhotoStore.pickerIntent(), req);
            return;
        } catch (Throwable t) {
            Log.w(TAG, "ACTION_PICK 不可用，退化 OPEN_DOCUMENT", t);
        }""",
    """    /** 打开系统相册选图（优先直达相册包，避免弹出"选择应用"面板）。 */
    private void pick(String name) {
        int req = PhotoStore.LOCK.equals(name) ? REQ_LOCK : REQ_HOME;
        append("打开相册（" + (req == REQ_LOCK ? "锁屏" : "桌面") + "）");
        try {
            Intent pick = PhotoStore.pickerIntent();
            String gallery = galleryPackage(pick);
            if (gallery != null) {
                pick.setPackage(gallery);
                append("相册应用=" + gallery);
            }
            startActivityForResult(pick, req);
            return;
        } catch (Throwable t) {
            Log.w(TAG, "ACTION_PICK 不可用，退化 OPEN_DOCUMENT", t);
        }""")

rep("""    @Override
    protected void onActivityResult(int request, int result, Intent data) {""",
    """    /** 找一个"相册"应用来处理选图 Intent（HyperOS 是 com.miui.gallery）。 */
    private String galleryPackage(Intent pick) {
        String[] prefer = {"com.miui.gallery", "com.google.android.apps.photos",
                "com.android.gallery3d", "org.codeaurora.gallery", "com.coloros.gallery3d"};
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(pick, 0);
            for (String want : prefer) {
                for (android.content.pm.ResolveInfo ri : list) {
                    String pkg = ri.activityInfo == null ? null : ri.activityInfo.packageName;
                    if (want.equals(pkg)) {
                        return pkg;
                    }
                }
            }
            for (android.content.pm.ResolveInfo ri : list) {
                String pkg = ri.activityInfo == null ? null : ri.activityInfo.packageName;
                if (pkg != null && pkg.toLowerCase(java.util.Locale.ROOT).contains("gallery")) {
                    return pkg;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "找相册失败", t);
        }
        return null;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')