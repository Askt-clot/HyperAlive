import deploy

out = deploy.su("dumpsys window windows | grep -E 'Window #|package=|title='")
print(out)

out2 = deploy.su("dumpsys SurfaceFlinger --list | grep -iE 'aod|keyguard|scrim|clock|wallpaper'")
print("--- SurfaceFlinger Layers ---")
print(out2)
