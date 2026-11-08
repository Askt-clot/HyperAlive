import deploy

out = deploy.su("cat /proc/9310/maps | grep -iE 'lsp|xposed|qihang'")
print(out)
