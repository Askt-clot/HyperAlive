import deploy

out = deploy.su("logcat -d --pid=9310 | grep -iE 'Qihang|LSPosed|Xposed' | head -n 30")
print(out)
