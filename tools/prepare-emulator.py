"""Fetch optional emulator packages using checksums from Google's SDK index."""
from pathlib import Path
import hashlib
import requests
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent.parent
sdk = root / '.tools/android-sdk'
client = requests.Session()
client.trust_env = False
packages = [
    ('https://dl.google.com/android/repository/repository2-3.xml', 'emulator', sdk / 'emulator', 'emulator'),
    ('https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml',
     'system-images;android-35;default;x86_64', sdk / 'system-images/android-35/default/x86_64', 'x86_64'),
]
for index, package_path, destination, prefix in packages:
    if (destination / 'source.properties').exists():
        print(package_path + ': ready', flush=True)
        continue
    response = client.get(index, timeout=30)
    response.raise_for_status()
    package = next(p for p in ET.fromstring(response.content) if p.get('path') == package_path and p.find('channelRef').get('ref') == 'channel-0')
    archives = package.findall('archives/archive')
    archive = next(a for a in archives if a.findtext('host-os') in (None, 'windows'))
    complete = archive.find('complete')
    checksum = complete.find('checksum')
    url = index.rsplit('/', 1)[0] + '/' + complete.findtext('url')
    target = root / '.tools' / (prefix + '-package.zip')
    if not target.exists() or hashlib.new(checksum.get('type'), target.read_bytes()).hexdigest() != checksum.text:
        print(package_path + ': downloading ' + complete.findtext('size') + ' bytes', flush=True)
        with client.get(url, stream=True, timeout=(20, 30)) as response:
            response.raise_for_status()
            with target.open('wb') as output:
                for block in response.iter_content(1024 * 1024):
                    output.write(block)
    if hashlib.new(checksum.get('type'), target.read_bytes()).hexdigest() != checksum.text:
        raise RuntimeError('SDK checksum mismatch')
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target) as bundle:
        for entry in bundle.infolist():
            relative = Path(entry.filename).relative_to(prefix)
            path = destination / relative
            if not path.resolve().is_relative_to(destination.resolve()):
                raise RuntimeError('Invalid SDK archive entry')
            if entry.is_dir():
                path.mkdir(parents=True, exist_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(entry) as source, path.open('wb') as output:
                    import shutil
                    shutil.copyfileobj(source, output)
    print(package_path + ': verified and installed', flush=True)
