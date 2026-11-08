import deploy

print("Power status:")
print(deploy.su("dumpsys power | grep -E 'mWakefulness|Display Power'"))

print("Window focus:")
print(deploy.su("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"))
