import xml.etree.ElementTree as ET
import re

tree = ET.parse(r'D:\ButtonHyperAlive\HyperAlive-debug\uidump.xml')
root = tree.getroot()
for node in root.iter('node'):
    b = node.get('bounds', '')
    res = node.get('resource-id', '')
    cls = node.get('class', '')
    m = re.findall(r'\[(\d+),(\d+)\]', b)
    if len(m) == 2:
        x1, y1 = int(m[0][0]), int(m[0][1])
        x2, y2 = int(m[1][0]), int(m[1][1])
        if y1 >= 300 and y2 <= 1100 and (x2 - x1 < 1200 or y2 - y1 < 2000):
            print(f'{cls} #{res} [{x1},{y1}][{x2},{y2}] text="{node.get("text", "")}"')
