#!/usr/bin/env python3
'''Summarize Caddy JSON access logs using only the Python standard library.'''

import json
import math
import re
import sys
from collections import defaultdict
from urllib.parse import urlsplit

rows = defaultdict(list)
for line in sys.stdin:
    try:
        start = line.find('{')
        if start < 0:
            continue
        entry = json.loads(line[start:])
        request = entry.get('request', {})
        path = urlsplit(str(request.get('uri', ''))).path
        if not path.startswith('/backend/'):
            continue
        path = re.sub(r'/[0-9]+(?=/|$)', '/:id', path)
        path = re.sub(r'/[0-9a-fA-F-]{24,}(?=/|$)', '/:key', path)
        method = str(request.get('method', 'GET'))
        rows[(method, path)].append(
            (float(entry.get('duration', 0.0)) * 1000.0, int(entry.get('status', 0)))
        )
    except (TypeError, ValueError, json.JSONDecodeError):
        pass

def percentile(values, fraction):
    return values[max(0, math.ceil(len(values) * fraction) - 1)]

print('%6s %7s %9s %9s %9s %9s %7s  endpoint' %
      ('count', 'errors', 'avg_ms', 'p50_ms', 'p95_ms', 'max_ms', 'method'))
for (method, path), samples in sorted(
        rows.items(), key=lambda item: max(sample[0] for sample in item[1]), reverse=True):
    values = sorted(sample[0] for sample in samples)
    errors = sum(1 for _, status in samples if status >= 500 or status == 0)
    print('%6d %7d %9.1f %9.1f %9.1f %9.1f %7s  %s' % (
        len(values), errors, sum(values) / len(values), percentile(values, 0.50),
        percentile(values, 0.95), values[-1], method, path))
if not rows:
    print('No /backend/ access-log entries were found in the selected interval.')
