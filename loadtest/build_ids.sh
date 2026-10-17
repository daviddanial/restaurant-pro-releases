#!/usr/bin/env bash
# Read-only: map generated studies (loadtest/studies.json) to DB study ids -> loadtest/study_ids.json
set -euo pipefail
cd "$(dirname "$0")"
docker exec teleradiology-postgres-1 psql -U telerad -d telerad -AtF$'\t' -c \
  "SELECT accession_number, id, study_instance_uid, instance_count, status FROM studies WHERE accession_number LIKE 'LT%'" > /tmp/claude-1000/lt_ids.tsv
python3 - <<'PY'
import json
gen = {s['accession']: s for s in json.load(open('studies.json'))}
out = []
for line in open('/tmp/claude-1000/lt_ids.tsv'):
    acc, sid, uid, n, st = line.rstrip('\n').split('\t')
    g = gen.get(acc)
    if not g or st != 'READY' or int(n) != g['images']:
        continue
    out.append({'accession': acc, 'id': sid, 'uid': uid, 'modality': g['modality'], 'compressed': g['compressed'],
                'images': int(n), 'mb': round(g['bytes'] / 1e6)})
json.dump(sorted(out, key=lambda s: s['accession']), open('study_ids.json', 'w'), indent=1)
from collections import Counter
print('ready studies:', dict(Counter(s['modality'] for s in out)))
PY
rm -f /tmp/claude-1000/lt_ids.tsv
