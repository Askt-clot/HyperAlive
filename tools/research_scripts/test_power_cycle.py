import deploy, time

print("Waking up device...")
deploy.su("input keyevent 26")
time.sleep(1)

print("Status after wake:")
print(deploy.su("dumpsys power | grep -E 'mWakefulness|Display Power'"))
print(deploy.su("dumpsys display | grep -E 'mScreenState|DisplayDeviceInfo' | head -n 10"))

print("Pressing power to sleep...")
deploy.su("input keyevent 26")
time.sleep(1)

print("Status after sleep 1s:")
print(deploy.su("dumpsys power | grep -E 'mWakefulness|Display Power'"))
print(deploy.su("dumpsys display | grep -E 'mScreenState|DisplayDeviceInfo' | head -n 10"))
