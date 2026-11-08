import deploy

out = deploy.su("dumpsys activity service com.android.systemui/.SystemUIService | grep -iE 'NotificationShade|clock|aod' | head -n 40")
print(out)
