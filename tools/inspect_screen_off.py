import sys
import os
sys.path.append(os.path.abspath('qihang_lsp'))
import deploy

cmd = r"""
logcat -d | grep -E "DisplayPowerControllerImpl|wait screen off" | tail -n 25
"""
print(deploy.su(cmd))
