import zipfile

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')

# Let's find all methods in DisplayPowerControllerImpl that call setWaitingScreenOffAnimator
# We know setWaitingScreenOffAnimator is in classes.dex
# Let's write a script to decompile DisplayPowerControllerImpl using jadx or javap if available
