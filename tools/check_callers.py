import zipfile

z = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar')
dex = z.read('classes.dex')

# Let's search for "setWaitingScreenOffAnimator" and find the method in DisplayPowerControllerImpl
# Let's write a java script to inspect all methods in DisplayPowerControllerImpl that call setWaitingScreenOffAnimator
# We can do this with Xposed right inside the device!
