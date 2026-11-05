#!/usr/bin/env python3
import re,subprocess,sys
from pathlib import Path
base=sys.argv[1];head=sys.argv[2] if len(sys.argv)>2 else "HEAD";missing=[]
d=subprocess.check_output(["git","diff","--unified=0",base,head,"--","*/src/main/res/values/strings.xml"],text=True,errors="ignore")
keys=set(re.findall(r'^\+.*?<string name="([^"]+)"',d,re.M))
zh="\n".join(p.read_text(errors="ignore") for p in (list(Path(".").glob("*/src/main/res/values-zh/strings.xml")) + list(Path(".").glob("*/src/main/res/values-zh-rCN/strings.xml"))))
for k in sorted(keys):
 if not re.search(r'<string name="'+re.escape(k)+r'"',zh):missing.append("missing zh-CN string: "+k)
kd=subprocess.check_output(["git","diff","--unified=0",base,head,"--","*.kt"],text=True,errors="ignore")
protocol=re.compile(r"(https?://|/data/|~/|[A-Z_]{3,}|allow-external-apps|termux\.properties|^[a-z0-9_.-]+$)")
for line in kd.splitlines():
 if line.startswith("+") and not line.startswith("+++") and any(x in line for x in ("Text(","title =","description =","label =")):
  for v in re.findall(r'"([A-Za-z][^"\\]{2,})"',line):
   if not protocol.search(v):missing.append("hardcoded UI text: "+v)
if missing:print("\n".join(sorted(set(missing))));sys.exit(1)
