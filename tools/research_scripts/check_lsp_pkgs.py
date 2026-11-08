import deploy

print(deploy.su('pm list packages | grep -i lsp'))
print(deploy.su('pm list packages -u | grep -i qihang'))
