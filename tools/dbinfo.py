import base64
import sqlite3

d = base64.b64decode(b''.join(open('mc3.b64', 'rb').read().split()))
open('mc3.db', 'wb').write(d)
c = sqlite3.connect('mc3.db')
q = "select module_pkg_name, apk_path, enabled from modules m join modules_state s using(module_pkg_name)"
for r in c.execute(q):
    print(r)
q2 = "select app_pkg_name from scope where module_pkg_name = ?"
for pkg in ('com.qihang.aod', 'com.alive.wallpaper.bridge'):
    print(pkg, '->', [r[0] for r in c.execute(q2, (pkg,))])