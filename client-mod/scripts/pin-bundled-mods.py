"""Legt fest, welche freien Optimierungs-Mods der TRS Client (Fabric) je Minecraft-Version mitliefert.

Schreibt fabric/bundled-mods.json: je Version die Datei, Größe und SHA-512 der passenden Modrinth-Version
(nur „release“, sonst „beta“). Der Build lädt genau diese Dateien (Hash wird geprüft) und bettet sie per
Jar-in-Jar ein. Neu festlegen: python scripts/pin-bundled-mods.py  (danach Datei prüfen und committen).

Nur Mods, deren Lizenz die Weitergabe in einem GPL-3.0-Mod erlaubt (siehe fabric/bundled-mods.md):
Lithium (LGPL-3.0), FerriteCore (MIT), ImmediatelyFast (LGPL-3.0+), ModernFix (LGPL-3.0), BadOptimizations (MIT).
Nicht dabei: Sodium (PolyForm Shield), Entity Culling (tr7zw Protective License), Iris (braucht Sodium),
More Culling (GPL-3.0, bräuchte zusätzlich Cloth Config).
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.parse
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSIONS = sorted(os.listdir(os.path.join(ROOT, 'fabric', 'versions')),
                  key=lambda v: [int(x) for x in v.split('.')])
# (Modrinth-ID, Mod-ID in fabric.mod.json, Name, Lizenz, Quelle, ab Minecraft)
MODS = [
    ('gvQqBUqZ', 'lithium', 'Lithium', 'LGPL-3.0-only', 'https://github.com/CaffeineMC/lithium', None),
    ('uXXizFIs', 'ferritecore', 'FerriteCore', 'MIT', 'https://github.com/malte0811/FerriteCore', None),
    ('5ZwdcRci', 'immediatelyfast', 'ImmediatelyFast', 'LGPL-3.0-or-later', 'https://github.com/RaphiMC/ImmediatelyFast', None),
    # Vor 1.20 bleibt ModernFix zusammen mit Lithium bei der Weltenerstellung hängen (siehe Launcher-Preset).
    ('nmDcB62a', 'modernfix', 'ModernFix', 'LGPL-3.0-only', 'https://github.com/embeddedt/ModernFix', '1.20'),
    ('g96Z4WVZ', 'badoptimizations', 'BadOptimizations', 'MIT', 'https://github.com/ItsThosea/BadOptimizations', None),
]
# Abhängigkeiten, die jede TRS-Instanz ohnehin hat.
PROVIDED = {'P7dR8mSH'}  # Fabric API


# Download-Zwischenspeicher – derselbe, den der Gradle-Build benutzt.
CACHE = os.path.join(os.path.expanduser('~'), '.gradle', 'caches', 'trsclient-bundled')


def ver(v):
    return [int(x) for x in v.split('.')]


def vkey(v):
    """Minecraft-/SemVer-Version vergleichbar machen (Vorabversionen wie 1.21-rc1 zählen vor 1.21)."""
    core = re.split(r'[-+]', v, maxsplit=1)[0]
    parts = [int(x) if x.isdigit() else 0 for x in core.split('.')]
    while len(parts) < 3:
        parts.append(0)
    pre = 0 if re.search(r'-(rc|pre|alpha|beta)', v) else 1
    return parts + [pre]


def matches_one(pred, mc):
    """Fabric-Versionsbedingung (ein Ausdruck, Leerzeichen = und) für die Minecraft-Version."""
    for p in pred.split():
        m = re.match(r'^(>=|<=|>|<|=|~|\^)?(.+)$', p)
        op, v = m.group(1) or '=', m.group(2)
        if v in ('*', 'x'):
            continue
        if 'x' in v.split('.') or v.endswith('.*'):
            prefix = [int(x) for x in v.replace('.*', '.x').split('.') if x.isdigit()]
            if vkey(mc)[:len(prefix)] != prefix:
                return False
            continue
        a, b = vkey(mc), vkey(v)
        if op == '>=' and not a >= b:
            return False
        if op == '<=' and not a <= b:
            return False
        if op == '>' and not a > b:
            return False
        if op == '<' and not a < b:
            return False
        if op == '=' and a[:3] != b[:3]:
            return False
        if op == '~' and not (a >= b and a[:2] == b[:2]):
            return False
        if op == '^' and not (a >= b and a[:1] == b[:1]):
            return False
    return True


def accepts(dep, mc):
    if dep is None:
        return True
    preds = dep if isinstance(dep, list) else [dep]
    return any(matches_one(p, mc) for p in preds)


def fetch(entry):
    """Datei in den Zwischenspeicher laden (Hash prüfen) und ihren Pfad liefern."""
    folder = os.path.join(CACHE, entry['sha512'][:16])
    path = os.path.join(folder, entry['file'])
    if not os.path.exists(path):
        os.makedirs(folder, exist_ok=True)
        subprocess.run(['curl', '-sSfL', '-o', path, entry['url']], check=True)
    with open(path, 'rb') as fh:
        digest = hashlib.sha512(fh.read()).hexdigest()
    if digest != entry['sha512']:
        os.remove(path)
        raise SystemExit('Hash stimmt nicht: ' + entry['file'])
    return path


def meta_of(entry):
    with zipfile.ZipFile(fetch(entry)) as z:
        return json.loads(z.read('fabric.mod.json').decode('utf-8'), strict=False)


def mutual_problems(entries):
    """Unverträglichkeiten der eingebauten Mods untereinander (fabric.mod.json: breaks, depends mit Versionen).
    Liefert [(id, Grund)] für Mods, die raus müssen."""
    metas = {e['id']: meta_of(e) for e in entries}
    versions = {e['id']: metas[e['id']].get('version', '') for e in entries}
    out = []
    for e in entries:
        meta = metas[e['id']]
        for other, pred in (meta.get('breaks') or {}).items():
            if other in versions and other != e['id'] and accepts(pred, versions[other]):
                out.append((other, f'{e["name"]} verträgt {other} {versions[other]} nicht (breaks {pred})'))
        for other, pred in (meta.get('depends') or {}).items():
            if other in versions and not accepts(pred, versions[other]):
                out.append((e['id'], f'braucht {other} {pred}, eingebaut ist {versions[other]}'))
    return out


def check_jar(entry, mc):
    """fabric.mod.json der Datei: passt sie zu dieser Version, sind alle Abhängigkeiten da?"""
    meta = meta_of(entry)
    depends = meta.get('depends', {})
    entry['modVersion'] = meta.get('version', '')
    if meta.get('id') != entry['id']:
        return f'Mod-ID {meta.get("id")} statt {entry["id"]}'
    if not accepts(depends.get('minecraft'), mc):
        return f'will minecraft {depends.get("minecraft")}'
    for dep in depends:
        if dep in ('minecraft', 'fabricloader', 'java', 'mixinextras') or dep.startswith('fabric'):  # MixinExtras bringt der Loader mit
            continue
        return f'braucht {dep}'
    return None


def get(url):
    out = subprocess.run(['curl', '-sSfL', '-A', 'theredstonee/trs-launcher (bundled-mods)', url], check=True,
                         capture_output=True).stdout
    return json.loads(out)


def best(project, mc):
    q = urllib.parse.urlencode({'loaders': json.dumps(['fabric']), 'game_versions': json.dumps([mc])})
    versions = get(f'https://api.modrinth.com/v2/project/{project}/version?{q}')
    for kind in ('release', 'beta'):
        for v in versions:
            if v['version_type'] == kind:
                return v
    return None


def main():
    only = set(sys.argv[1:])
    result = {}
    for mc in VERSIONS:
        if only and mc not in only:
            continue
        entries = []
        for pid, mod_id, name, lic, src, since in MODS:
            if since and ver(mc) < ver(since):
                continue
            v = best(pid, mc)
            if v is None:
                continue
            deps = [d['project_id'] for d in v.get('dependencies', [])
                    if d.get('dependency_type') == 'required' and d.get('project_id') and d['project_id'] not in PROVIDED]
            if deps:
                print(f'{mc}: {name} {v["version_number"]} übersprungen – braucht {deps}')
                continue
            f = next((x for x in v['files'] if x.get('primary')), v['files'][0])
            entry = {'id': mod_id, 'name': name, 'version': v['version_number'], 'file': f['filename'],
                     'url': f['url'], 'sha512': f['hashes']['sha512'], 'size': f['size'], 'license': lic,
                     'source': src}
            problem = check_jar(entry, mc)
            if problem:
                print(f'{mc}: {name} {v["version_number"]} übersprungen – {problem}')
                continue
            entries.append(entry)
        # Untereinander verträglich? Sonst die betroffene Mod weglassen und erneut prüfen.
        while True:
            problems = mutual_problems(entries)
            if not problems:
                break
            drop, why = problems[0]
            print(f'{mc}: {drop} weggelassen – {why}')
            entries = [e for e in entries if e['id'] != drop]
        result[mc] = entries
        print(f'{mc}: ' + ', '.join(f'{e["name"]} {e["version"]}' for e in entries))
    path = os.path.join(ROOT, 'fabric', 'bundled-mods.json')
    if only and os.path.exists(path):
        old = json.load(open(path, encoding='utf-8'))
        old.update(result)
        result = old
    ordered = {k: result[k] for k in sorted(result, key=ver)}
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(ordered, fh, indent=1, ensure_ascii=False)
        fh.write('\n')


if __name__ == '__main__':
    main()
