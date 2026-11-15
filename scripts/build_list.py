#!/usr/bin/env python3
"""
NumberCheck daily list builder.

READS ONLY: runs one SELECT on the number_lookup view with the read-only
number_lookup_reader login. Nothing in the database is created, changed or deleted.

Writes into web/data/:
  manifest.json            what the phones check first (tiny)
  full-<version>.bin       every number, scrambled: 5 bytes each, sorted
  delta-<from>-<to>.bin    only the changes between two days (what phones normally download)

Environment:
  NEON_READER_URL   connection string of number_lookup_reader   (GitHub secret)
  NUMBERCHECK_SALT  secret text that scrambles the numbers       (GitHub secret, also in agents' setup code)
  SITE_URL          the live website, to fetch yesterday's list   (set by the workflow)
  FORCE             "true" to publish even if the list shrank a lot
"""
import hashlib, json, os, re, struct, sys, urllib.request
from datetime import datetime, timezone

QUERY = "SELECT contact_clean FROM number_lookup WHERE NOT deleted AND contact_clean <> ''"
OUT = os.path.join(os.path.dirname(__file__), '..', 'web', 'data')
KEEP_DELTAS = 21          # phones that were away up to ~3 weeks still get small downloads
HASH_BYTES = 5
VALID = re.compile(r'^0\d{9}$')


def normalize(raw):
    """Same rule as the apps (contact_clean should already be 0XXXXXXXXX)."""
    d = re.sub(r'\D', '', str(raw or ''))
    if d.startswith('00233'):
        d = d[5:]
    elif d.startswith('233') and len(d) >= 12:
        d = d[3:]
    if len(d) == 9 and d[0] != '0':
        d = '0' + d
    return d if VALID.match(d) else None


def scramble(salt, n):
    return int.from_bytes(hashlib.sha256(f'ncx1:{salt}:{n}'.encode()).digest()[:HASH_BYTES], 'big')


def pack(values):
    return b''.join(v.to_bytes(HASH_BYTES, 'big') for v in values)


def unpack(blob):
    return {int.from_bytes(blob[i:i + HASH_BYTES], 'big') for i in range(0, len(blob) - HASH_BYTES + 1, HASH_BYTES)}


def fetch(url):
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            return r.read()
    except Exception as e:
        print(f'  (could not fetch {url}: {e})')
        return None


def read_numbers(conn_str):
    import psycopg
    with psycopg.connect(conn_str, connect_timeout=30) as conn:
        conn.read_only = True   # belt and braces: this session cannot write
        with conn.cursor() as cur:
            cur.execute(QUERY)
            return [row[0] for row in cur.fetchall()]


def main():
    salt = os.environ['NUMBERCHECK_SALT'].strip()
    site = os.environ.get('SITE_URL', '').rstrip('/')
    force = os.environ.get('FORCE', '').lower() == 'true'
    os.makedirs(OUT, exist_ok=True)

    rows = read_numbers(os.environ['NEON_READER_URL'])
    numbers = {n for n in (normalize(r) for r in rows) if n}
    current = {scramble(salt, n) for n in numbers}
    print(f'Read {len(rows)} rows -> {len(numbers)} unique valid numbers')

    salt_check = hashlib.sha256(f'check:{salt}'.encode()).hexdigest()[:12]
    prev_manifest, prev_set, deltas = None, None, []

    if site:
        raw = fetch(f'{site}/data/manifest.json')
        if raw:
            prev_manifest = json.loads(raw)
            if prev_manifest.get('saltCheck') == salt_check:
                blob = fetch(f"{site}/data/{prev_manifest['full']}")
                if blob is not None:
                    prev_set = unpack(blob)
                for d in prev_manifest.get('deltas', []):
                    b = fetch(f"{site}/data/{d['file']}")
                    if b is not None:
                        open(os.path.join(OUT, d['file']), 'wb').write(b)
                        deltas.append(d)
            else:
                print('Salt changed (or first run): publishing a fresh full list only')

    if prev_set is not None and not force and len(current) < 0.8 * len(prev_set):
        sys.exit(f'STOPPED: list shrank from {len(prev_set)} to {len(current)}. '
                 'If that is correct, run the workflow manually with force = true.')

    if prev_set is not None and prev_set == current:
        version = prev_manifest['version']
        print('No changes since last list; keeping version', version)
    else:
        version = int(datetime.now(timezone.utc).strftime('%Y%m%d%H%M'))
        if prev_manifest:
            version = max(version, prev_manifest['version'] + 1)   # always move forward
        if prev_set is not None:
            adds, removes = sorted(current - prev_set), sorted(prev_set - current)
            name = f"delta-{prev_manifest['version']}-{version}.bin"
            data = struct.pack('>I', len(adds)) + pack(adds) + struct.pack('>I', len(removes)) + pack(removes)
            if len(data) > len(current) * HASH_BYTES // 2:
                print('Changes are bigger than half the list: phones will download the full list instead')
                deltas = []          # break the chain so phones fetch the full list
            else:
                open(os.path.join(OUT, name), 'wb').write(data)
                deltas.append({'from': prev_manifest['version'], 'to': version, 'file': name, 'bytes': len(data)})
            print(f'Changes: +{len(adds)} / -{len(removes)}  ({len(data)} bytes)')

    deltas = deltas[-KEEP_DELTAS:]
    keep = {d['file'] for d in deltas}
    full_name = f'full-{version}.bin'
    for f in os.listdir(OUT):
        if (f.startswith('delta-') and f not in keep) or (f.startswith('full-') and f != full_name):
            os.remove(os.path.join(OUT, f))

    open(os.path.join(OUT, full_name), 'wb').write(pack(sorted(current)))
    manifest = {
        'format': 1, 'version': version, 'total': len(current), 'hashBytes': HASH_BYTES,
        'updated': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ') if not (prev_manifest and version == prev_manifest['version']) else prev_manifest['updated'],
        'checked': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'saltCheck': salt_check, 'full': full_name, 'fullBytes': len(current) * HASH_BYTES, 'deltas': deltas,
    }
    json.dump(manifest, open(os.path.join(OUT, 'manifest.json'), 'w'), indent=1)
    print(f'Published version {version}: {len(current)} numbers, full list {len(current) * HASH_BYTES // 1024} KB')


if __name__ == '__main__':
    main()
