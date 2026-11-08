import deploy

print(deploy.su('ps -A | grep -i aod', check=False))
print(deploy.su('pm path com.miui.aod', check=False))
