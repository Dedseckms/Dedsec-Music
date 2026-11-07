"""Install checksum-verified portable Android build tools inside this project."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import subprocess
import os
import time
import requests
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)


def session():
    result = requests.Session()
    result.trust_env = False
    return result


def install(label, url, checksum, destination):
    if destination.exists():
        print(label + ': already installed', flush=True)
        return
    archive = TOOLS / (label + '.zip')
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        print(label + ': downloading', flush=True)
        for attempt in range(20):
            try:
                offset = archive.stat().st_size if archive.exists() else 0
                headers = {'Range': 'bytes={}-'.format(offset), 'Accept-Encoding': 'identity'}
                with session() as client, client.get(url, headers=headers, stream=True, timeout=(15, 25)) as response:
                    response.raise_for_status()
                    append = response.status_code == 206 and offset > 0
                    with archive.open('ab' if append else 'wb') as target:
                        for block in response.iter_content(256 * 1024):
                            target.write(block)
                break
            except requests.RequestException:
                print(label + ': retry ' + str(attempt + 1), flush=True)
                if attempt == 19:
                    raise
                time.sleep(2)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        raise RuntimeError(label + ': checksum mismatch')
    print(label + ': checksum verified; extracting', flush=True)
    with zipfile.ZipFile(archive) as bundle:
        for name in bundle.namelist():
            if not (TOOLS / name).resolve().is_relative_to(TOOLS.resolve()):
                raise RuntimeError('Unsafe archive entry')
        bundle.extractall(TOOLS / ('unpack-' + label))
    extracted = TOOLS / ('unpack-' + label)
    children = list(extracted.iterdir())
    actual = children[0] if len(children) == 1 and children[0].is_dir() else extracted
    destination.parent.mkdir(exist_ok=True, parents=True)
    actual.rename(destination)
    print(label + ': ready', flush=True)


with session() as client:
    response = client.get('https://corretto.aws/downloads/latest_sha256/amazon-corretto-21-x64-windows-jdk.zip', timeout=30)
    response.raise_for_status()
    jdk = {'download_url': 'https://corretto.aws/downloads/latest/amazon-corretto-21-x64-windows-jdk.zip', 'sha256_hash': response.text.strip()}
    response = client.get('https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256', timeout=30)
    response.raise_for_status()
    gradle_hash = response.text.strip()

with ThreadPoolExecutor(max_workers=3) as pool:
    jobs = [pool.submit(install, 'jdk21', jdk['download_url'], jdk['sha256_hash'], TOOLS / 'jdk'),
            pool.submit(install, 'gradle813', 'https://downloads.gradle.org/distributions/gradle-8.13-bin.zip',
                        gradle_hash, TOOLS / 'gradle'),
            pool.submit(install, 'android-cli', 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip',
                        '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a',
                        TOOLS / 'android-sdk/cmdline-tools/latest')]
    for job in jobs:
        job.result()

environment = dict(os.environ, JAVA_HOME=str(TOOLS / 'jdk'), ANDROID_HOME=str(TOOLS / 'android-sdk'))
environment['PATH'] = str(TOOLS / 'jdk/bin') + os.pathsep + environment['PATH']
sdkmanager = TOOLS / 'android-sdk/cmdline-tools/latest/bin/sdkmanager.bat'
result = subprocess.run([str(sdkmanager), '--sdk_root=' + str(TOOLS / 'android-sdk'),
                         'platforms;android-36', 'build-tools;35.0.0', 'platform-tools'],
                        input='y\n' * 200, text=True, env=environment)
if result.returncode:
    raise SystemExit(result.returncode)
(ROOT / 'local.properties').write_text('sdk.dir=' + str(TOOLS / 'android-sdk').replace('\\', '/').replace(':', '\\:') + '\n')
print('Portable Android toolchain ready.', flush=True)
