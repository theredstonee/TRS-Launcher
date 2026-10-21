# Writes pitch/media/qr-download.svg (QR code for the download page, no network at runtime).
# Usage: python pitch/tools/make-qr.py   (needs segno)
import os
import segno

URL = 'https://trs-launcher.theredstonee.de/download'
OUT = os.path.join(os.path.dirname(__file__), '..', 'media', 'qr-download.svg')
qr = segno.make(URL, error='m', micro=False)
qr.save(OUT, kind='svg', xmldecl=False, svgclass=None, lineclass=None, omitsize=True, border=2, dark='#111116', light='#ffffff')
print(OUT, qr.version, qr.error)
