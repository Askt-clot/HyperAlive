import deploy

print("miui.systemui.plugin:", deploy.su('pm path miui.systemui.plugin', check=False).strip())
print("com.miui.aod:", deploy.su('pm path com.miui.aod', check=False).strip())
