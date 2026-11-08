import deploy

cmd = """
dumpsys activity top
"""
# Or dumpsys window windows
print("=== DUMP WINDOWS ===")
out = deploy.su("dumpsys window windows | grep -iE 'NotificationShade|AOD|StatusBar' | head -n 30")
print(out)
