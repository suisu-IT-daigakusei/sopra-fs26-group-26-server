#!/usr/bin/env python3
'''Create, structurally validate, and retain atomic PostgreSQL backups for Cabo.'''

import datetime
import os
import pathlib
import subprocess
import sys
import time

script_dir = pathlib.Path(__file__).resolve().parent
deploy_dir = script_dir.parent
env_file = pathlib.Path(os.getenv('CABO_ENV_FILE', deploy_dir / '.env'))
compose_file = pathlib.Path(
    os.getenv('CABO_COMPOSE_FILE', deploy_dir / 'compose.production.yaml')
)
backup_dir = pathlib.Path(os.getenv('CABO_BACKUP_DIR', '/var/lib/cabo-backups'))

try:
    retention_days = int(os.getenv('CABO_BACKUP_RETENTION_DAYS', '14'))
    if retention_days < 0:
        raise ValueError
except ValueError:
    sys.exit('CABO_BACKUP_RETENTION_DAYS must be a non-negative integer.')

if not env_file.is_file():
    sys.exit(f'Missing environment file: {env_file}')

backup_dir.mkdir(parents=True, exist_ok=True)
os.chmod(backup_dir, 0o700)
timestamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
destination = backup_dir / f'cabo-{timestamp}.dump'
partial = destination.with_suffix('.dump.partial')
compose = [
    'docker', 'compose', '--env-file', str(env_file), '-f', str(compose_file)
]

try:
    with partial.open('xb') as output:
        subprocess.run(
            compose + [
                'exec', '-T', 'database', 'sh', '-ec',
                'exec pg_dump --username=$POSTGRES_USER --dbname=$POSTGRES_DB '
                '--format=custom --no-owner --no-privileges',
            ],
            check=True,
            stdout=output,
        )
    if partial.stat().st_size == 0:
        raise RuntimeError('pg_dump produced an empty archive')
    with partial.open('rb') as archive:
        subprocess.run(
            compose + ['exec', '-T', 'database', 'pg_restore', '--list'],
            check=True,
            stdin=archive,
            stdout=subprocess.DEVNULL,
        )
    os.chmod(partial, 0o600)
    partial.replace(destination)
finally:
    partial.unlink(missing_ok=True)

cutoff = time.time() - retention_days * 86400
for candidate in backup_dir.glob('cabo-*.dump'):
    if candidate != destination and candidate.stat().st_mtime < cutoff:
        candidate.unlink()

size_mib = destination.stat().st_size / 1024 / 1024
print(f'Structurally validated PostgreSQL backup (TOC readable): {destination} ({size_mib:.1f} MiB)')
