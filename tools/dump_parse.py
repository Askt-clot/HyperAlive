import xml.etree.ElementTree as ET

tree = ET.parse(r'D:\ButtonHyperAlive\HyperAlive-debug\uidump.xml')
root = tree.getroot()

def print_tree(node, depth=0):
    indent = '  ' * depth
    cls = node.get('class', '').split('.')[-1]
    res = node.get('resource-id', '').split('/')[-1]
    text = node.get('text', '')
    bounds = node.get('bounds', '')
    desc = f"{cls}"
    if res: desc += f" #{res}"
    if text: desc += f" \"{text}\""
    desc += f" {bounds}"
    print(indent + desc)
    for c in node:
        print_tree(c, depth + 1)

for node in root.iter('node'):
    if 'keyguard_translation_info' in node.get('resource-id', ''):
        print('=== FOUND keyguard_translation_info ===')
        print_tree(node)

for node in root.iter('node'):
    if 'aod_root_view' in node.get('resource-id', ''):
        print('=== FOUND aod_root_view ===')
        print_tree(node)
