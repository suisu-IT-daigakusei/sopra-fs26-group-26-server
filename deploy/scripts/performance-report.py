#!/usr/bin/env python3
'''Produce a repeatable Cabo host, container, PostgreSQL, and HTTP report.'''

import argparse
import os
import pathlib
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument('hours', type=int, nargs='?', default=6)
args = parser.parse_args()
if args.hours < 1:
    parser.error('hours must be positive')

script_dir = pathlib.Path(__file__).resolve().parent
deploy_dir = script_dir.parent
env_file = pathlib.Path(os.getenv('CABO_ENV_FILE', deploy_dir / '.env'))
compose_file = pathlib.Path(
    os.getenv('CABO_COMPOSE_FILE', deploy_dir / 'compose.production.yaml')
)
if not env_file.is_file():
    sys.exit(f'Missing environment file: {env_file}')

compose = [
    'docker', 'compose', '--env-file', str(env_file), '-f', str(compose_file)
]

def section(title):
    print(f'\n== {title} ==', flush=True)

def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)

section('Service state')
run(compose + ['ps'])

container_ids = subprocess.check_output(compose + ['ps', '-q'], text=True).split()
if container_ids:
    section('One-shot container resources')
    run([
        'docker', 'stats', '--no-stream', '--format',
        'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.PIDs}}'
        '\t{{.NetIO}}\t{{.BlockIO}}',
    ] + container_ids)

section('Host resources')
run(['free', '-h'])
run(['uptime'])

database_sql = '''
SELECT numbackends, xact_commit, xact_rollback, blks_read, blks_hit,
       round(100.0 * blks_hit / NULLIF(blks_hit + blks_read, 0), 2)
           AS cache_hit_pct,
       temp_files, pg_size_pretty(temp_bytes) AS temp_bytes, deadlocks
FROM pg_stat_database
WHERE datname = current_database();
'''
section('PostgreSQL database counters')
run(
    compose + [
        'exec', '-T', 'database', 'sh', '-ec',
        'exec psql -X -v ON_ERROR_STOP=1 -U $POSTGRES_USER '
        '-d $POSTGRES_DB -P pager=off',
    ],
    input=database_sql,
    text=True,
)

queries_sql = '''
SELECT calls,
       round(total_exec_time::numeric, 1) AS total_ms,
       round(mean_exec_time::numeric, 2) AS mean_ms,
       rows,
       shared_blks_hit,
       shared_blks_read,
       left(regexp_replace(query, E'[\\n\\r\\t ]+', ' ', 'g'), 180) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC
LIMIT 20;
'''
section('PostgreSQL top normalized queries since last reset')
run(
    compose + [
        'exec', '-T', 'database', 'sh', '-ec',
        'exec psql -X -v ON_ERROR_STOP=1 -U $POSTGRES_USER '
        '-d $POSTGRES_DB -P pager=off',
    ],
    input=queries_sql,
    text=True,
)

section(f'Caddy backend latency for the last {args.hours} hour(s)')
caddy_id = subprocess.check_output(compose + ['ps', '-q', 'caddy'], text=True).strip()
if not caddy_id:
    print('Caddy is not running.')
    sys.exit()
logs = subprocess.Popen(
    ['docker', 'logs', '--since', f'{args.hours}h', caddy_id],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
)
assert logs.stdout is not None
try:
    run([sys.executable, str(script_dir / 'caddy-latency-report.py')], stdin=logs.stdout)
finally:
    logs.stdout.close()
if logs.wait() != 0:
    sys.exit('docker logs failed')
