import deploy

print("=== WALLPAPER STATE ===")
print(deploy.su("dumpsys wallpaper | grep -iE 'mWallpaperComponent|which|visible' | head -n 30"))

print("=== SYSTEMUI AOD / KEYGUARD STATE ===")
print(deploy.su("dumpsys activity service com.android.systemui/.SystemUIService | grep -iE 'mDoze|mDozing|mAod|isDozing|KeyguardState' | head -n 40"))
