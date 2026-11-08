import deploy

script = """
import deploy
print(deploy.su("dumpsys window windows | grep -iE 'NotificationShade|Keyguard|AOD' | head -n 30"))
"""
print(deploy.su("dumpsys activity service com.android.systemui/.SystemUIService | grep -iE 'keyguard_info_layer|AODView' | head -n 30", check=False))
