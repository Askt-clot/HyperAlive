import deploy

pid = deploy.su('pidof com.android.systemui').strip()
print("SystemUI PID:", pid)
out = deploy.su(f'cat /proc/{pid}/maps | grep -iE "aod|plugin"', check=False)
print(out)
