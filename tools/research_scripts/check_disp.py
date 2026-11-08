import deploy

print(deploy.su("dumpsys power | grep -E 'mWakefulness|Display Power'"))
print(deploy.su("dumpsys display | grep -E 'mScreenState|DisplayDeviceInfo' | head -n 8"))
