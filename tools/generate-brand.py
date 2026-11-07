"""Export the Android icon through the portable JDK's vector renderer."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
java = root / '.tools' / 'jdk' / 'bin' / 'java.exe'
subprocess.run([str(java), str(root / 'tools' / 'GenerateBrand.java')], cwd=str(root), check=True)
