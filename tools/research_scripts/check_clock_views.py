import deploy

# Check all classes in keyguard_info_layer
out = deploy.su("logcat -d | grep -iE 'keyguard_translation_info|keyguard_info_layer|MiuiKeyguard|MiClock' | head -n 40", check=False)
print("=== LOGS ===")
print(out)
