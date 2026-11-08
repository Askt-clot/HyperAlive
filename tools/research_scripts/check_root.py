import deploy

script = """
import deploy
out = deploy.su("logcat -d | grep -iE 'AOD sweep.*root' | tail -n 10", check=False)
print(out)
"""
print(deploy.su("logcat -d | grep -iE 'AOD sweep.*root' | tail -n 5", check=False))
