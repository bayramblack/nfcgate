"""Build the public APK/manifest pair; publish latest.json last, atomically."""
from pathlib import Path
import hashlib
import json
import shutil

project = Path(__file__).resolve().parents[1]
build = project / 'app/build/outputs/apk/distribution'
metadata = json.loads((build / 'output-metadata.json').read_text(encoding='utf-8'))
artifact = metadata['elements'][0]
apk = build / artifact['outputFile']
output = project / 'build/distribution'
output.mkdir(parents=True, exist_ok=True)
filename = f"UnderNet-{artifact['versionName']}-{artifact['versionCode']}.apk"
shutil.copy2(apk, output / filename)
manifest = {
    'versionCode': artifact['versionCode'],
    'versionName': artifact['versionName'],
    'minSdk': 21,
    'url': 'https://relay.undernet.work/downloads/' + filename,
    'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'size': apk.stat().st_size,
}
(output / 'latest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(json.dumps(manifest, indent=2))
