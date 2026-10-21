# Prepares the pitch media from the release screenshots (public/news) into pitch/media/*.webp.
# Game screenshots (854x480) are doubled with nearest-neighbour so they stay crisp when the browser scales them.
# Usage: python pitch/tools/prepare-media.py   (needs Pillow; output is committed, the build only copies it)
import os
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'pitch', 'media')
N = os.path.join(ROOT, 'public', 'news')

# name: (source, max width[, crop box])
MEDIA = {
    'home': ('0.4.0/start.png', 1920),
    'featured': ('0.26.0/featured-carousel.png', 1600),
    'discover': ('0.15.2/discover-source.png', 1600),
    'minimap': ('0.13.0/minimap.png', 0),
    'worldmap': ('0.13.0/world-map.png', 0),
    'pvp-hud': ('0.9.0/pvp-hud.png', 0),
    'trs-menu': ('0.3.0/trs-menu.png', 0),
    'redstone-overlay': ('0.5.0/redstone-overlay.png', 0),
    'circuit-ghost': ('0.10.0/circuit-ghost.png', 0),
    'cape-hd-ingame': ('0.25.0/cape-hd-ingame.png', 0),
    'crown': ('0.15.0/game-redstone-crown.png', 0),
    'wings': ('0.23.0/wings-ingame.png', 0),
    'emote-wheel': ('0.5.0/emote-wheel.png', 0),
    'chat': ('0.7.0/chat.png', 1600),
    'friends': ('0.7.0/friends.png', 1600),
    'worlds': ('0.8.0/worlds.png', 1600),
    'share-result': ('0.12.0/share-result.png', 1600),
    'clip-trim': ('0.7.0/clip-trim.png', 1600),
    'screenshot-editor': ('0.14.0/screenshot-editor.png', 0),
    'winter-home': ('0.20.0/winter-home.png', 1600),
    'halloween-library': ('0.24.0/launcher-halloween-library.png', 1600),
    'creator-dashboard': ('0.22.0/creator-dashboard.png', 1600),
    'fan-symbol': ('0.22.0/fan-symbol-ingame.png', 1708),
    'mobile-app': ('0.18.0/mobile-app.png', 1600, (464, 30, 815, 773)),
    'big-picture': ('0.18.0/big-picture.png', 1600),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for name, spec in MEDIA.items():
        src, maxw = spec[0], spec[1]
        im = Image.open(os.path.join(N, src)).convert('RGB')
        if len(spec) > 2:
            im = im.crop(spec[2])
        if maxw == 0:
            # 854x480 game shot -> 2x nearest (crisp pixels)
            im = im.resize((im.width * 2, im.height * 2), Image.NEAREST)
            q = 88
        else:
            if im.width > maxw:
                im = im.resize((maxw, round(im.height * maxw / im.width)), Image.LANCZOS)
            q = 92
        dst = os.path.join(OUT, name + '.webp')
        im.save(dst, 'WEBP', quality=q, method=6)
        total += os.path.getsize(dst)
        print(f'{name:20s} {im.width}x{im.height} {os.path.getsize(dst) // 1024} KB')
    icon = Image.open(os.path.join(ROOT, 'public', 'icon.png'))
    icon.save(os.path.join(OUT, 'icon.png'))
    print(f'total {total // 1024} KB')


if __name__ == '__main__':
    main()
