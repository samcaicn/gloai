# -*- coding: utf-8 -*-
import os, sys, json, logging
from loguru import logger
logger.remove()
try:
    from androguard.core.apk import APK
    from androguard.core.dex import DEX
except Exception as e:
    print("import fail", e); sys.exit(1)

APK_PATH = r'C:/d/aizhibo/vbaokebase.apk'
OUT = r'C:/d/aizhibo/vboke/analysis'
os.makedirs(OUT, exist_ok=True)

a = APK(APK_PATH)
info = {
    "package": a.get_package(),
    "version_name": a.get_androidversion_name(),
    "version_code": a.get_androidversion_code(),
    "min_sdk": a.get_min_sdk_version(),
    "target_sdk": a.get_target_sdk_version(),
    "app_name_label": a.get_app_name(),
    "activities": a.get_activities(),
    "services": a.get_services(),
    "receivers": a.get_receivers(),
    "permissions": a.get_permissions(),
    "main_activity": a.get_main_activity(),
}
with open(os.path.join(OUT,'manifest.json'),'w',encoding='utf-8') as f:
    json.dump(info,f,ensure_ascii=False,indent=2)
print("=== MANIFEST ===")
print(json.dumps(info,ensure_ascii=False,indent=2)[:2500])

# DEX classes + strings
import zipfile
z = zipfile.ZipFile(APK_PATH)
dex_names = [n for n in z.namelist() if n.startswith('classes') and n.endswith('.dex')]
all_classes=[]; all_strings=set()
for dn in dex_names:
    try:
        d = DEX(z.read(dn))
        for c in d.get_classes():
            all_classes.append(c.get_name())
    except Exception as e:
        print("dex parse warn",dn,e)
with open(os.path.join(OUT,'classes.json'),'w',encoding='utf-8') as f:
    json.dump(sorted(set(all_classes)),f,ensure_ascii=False,indent=1)
# raw strings from all dex bytes (cheap, robust)
strings=set()
import re
for dn in dex_names:
    data=z.read(dn)
    for m in re.finditer(rb'[\x20-\x7e]{4,}', data):
        s=m.group().decode('ascii','ignore')
        strings.add(s)
with open(os.path.join(OUT,'strings.txt'),'w',encoding='utf-8') as f:
    f.write('\n'.join(sorted(strings)))
# chinese strings (utf-8 + utf-16le scan)
cn=set()
for dn in dex_names:
    data=z.read(dn)
    for m in re.finditer(rb'(?:[\xe4-\xe9][\x80-\xbf]{2}){2,}', data):
        try: cn.add(m.group().decode('utf-8'))
        except: pass
    for m in re.finditer(rb'(?:[\x00-\xff]\x4e|[\x00-\xff]\x5e|[\x00-\xff]\x6e)[\x00-\xff]', data):
        pass
with open(os.path.join(OUT,'chinese.txt'),'w',encoding='utf-8') as f:
    f.write('\n'.join(sorted(cn)))
print("classes:",len(set(all_classes)),"strings:",len(strings),"chinese:",len(cn))
