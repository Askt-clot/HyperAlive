import json, sys

sys.stdout.reconfigure(encoding='utf-8')
with open(r"C:\Users\<user>\.gemini\antigravity\brain\82857e28-4d0f-476c-b8f3-4539ae9c5ce2\.system_generated\logs\transcript.jsonl", "r", encoding="utf-8") as f:
    for line in f:
        obj = json.loads(line)
        idx = obj.get("step_index", 0)
        if idx in [611, 612, 613, 614, 615, 1040, 1041, 1042, 1043, 1044, 1045]:
            print(f"Step {idx} [{obj.get('source')}] {obj.get('type')}: {str(obj.get('content'))[:150]}")
