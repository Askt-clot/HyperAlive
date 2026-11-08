import deploy

print(deploy.su('lspctl --help', check=False).strip())
