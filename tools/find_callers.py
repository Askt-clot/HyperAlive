import zipfile

def search_method_in_jar(jar_path, method_name):
    print("Checking", jar_path)
    z = zipfile.ZipFile(jar_path)
    for name in z.namelist():
        if name.endswith('.dex'):
            data = z.read(name)
            if method_name.encode('utf-8') in data:
                print("Found", method_name, "in", jar_path, name)

search_method_in_jar('qihang_lsp/tools/miui-services.jar', 'setWaitingScreenOffAnimator')
search_method_in_jar('qihang_lsp/tools/services.jar', 'setWaitingScreenOffAnimator')
search_method_in_jar('qihang_lsp/tools/services.jar', 'notifyScreenOffAnimatorEnd')
search_method_in_jar('qihang_lsp/tools/miui-services.jar', 'notifyScreenOffAnimatorEnd')
