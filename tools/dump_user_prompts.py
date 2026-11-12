import json
import sys

sys.stdout.reconfigure(encoding='utf-8')
with open(r"C:\Users\<user>\.gemini\antigravity\brain\82857e28-4d0f-476c-b8f3-4539ae9c5ce2\.system_generated\logs\transcript.jsonl", "r", encoding="utf-8") as f:
    for line in f:
        obj = json.loads(line)
        if obj.get("source") == "USER_EXPLICIT":
            content = obj.get('content', '')
            print(f"Step {obj.get('step_index')}: {content[:120].replace(chr(10), ' ')}")
