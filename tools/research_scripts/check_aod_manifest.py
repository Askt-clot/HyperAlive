import zipfile
import xml.etree.ElementTree as ET
import deploy

out = deploy.su('dumpsys package com.miui.aod | grep -E "userId|versionName|dataDir|processName"', check=False)
print("=== dumpsys package com.miui.aod ===")
print(out)
