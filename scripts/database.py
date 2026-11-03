#!/usr/bin/env python3
"""Local Docker stack and portable, offline database maintenance. No third-party Python modules."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / '.env.mysql'
PROJECT = 'feishu-claude'
CONTAINER = 'feishu-claude-mysql'


def run(args, *, env=None, **kwargs):
    return subprocess.run(args, check=True, env=env, **kwargs)


def config():
    if not ENV_FILE.exists():
        raise ValueError('Run: python3 scripts/database.py init')
    result = {}
    for line in ENV_FILE.read_text(encoding='utf-8').splitlines():
        if line.strip() and not line.startswith('#'):
            key, value = line.split('=', 1)
            result[key] = json.loads(value) if value.startswith('"') else value
    return result


def compose(*args, **kwargs):
    return run(['docker', 'compose', '-p', PROJECT, '--env-file', str(ENV_FILE), '-f', str(ROOT / 'compose.yml'), *args], **kwargs)


def dbname(): return config().get('FEISHU_MYSQL_DATABASE', 'feishu_claude')

def mysql(sql, *, database=None, root=False):
    settings = config()
    env = dict(os.environ, MYSQL_PWD=settings['FEISHU_MYSQL_ROOT_PASSWORD' if root else 'FEISHU_MYSQL_PASSWORD'])
    return run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', CONTAINER, 'mysql', '--default-character-set=utf8mb4',
                '-uroot' if root else '-ufeishu', '-N', '-B', database or dbname()], env=env, input=sql.encode(), capture_output=True).stdout.decode()


def init():
    if ENV_FILE.exists():
        print('Existing .env.mysql retained (passwords not changed).')
        return
    values = {'FEISHU_MYSQL_PASSWORD': secrets.token_urlsafe(32), 'FEISHU_MYSQL_ROOT_PASSWORD': secrets.token_urlsafe(40),
              'FEISHU_MYSQL_PORT': '3307', 'FEISHU_MYSQL_DATABASE': 'feishu_claude', 'FEISHU_FRONTEND_PORT': '18080', 'FEISHU_BACKEND_PORT': '18081',
              'LOCAL_UID': str(getattr(os, 'getuid', lambda: 1000)()), 'LOCAL_GID': str(getattr(os, 'getgid', lambda: 1000)()),
              'WORKSPACE_ROOT': str(ROOT)}
    with ENV_FILE.open('x', encoding='utf-8') as stream:
        for key, value in values.items(): stream.write(key + '=' + json.dumps(value, ensure_ascii=False) + '\n')
    ENV_FILE.chmod(0o600)
    print('Created private .env.mysql. Passwords are not printed or committed.')


def new_store(name):
    import re
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,47}", name): raise ValueError('Use a lowercase database name, max 48 characters')
    if mysql("SELECT SCHEMA_NAME FROM information_schema.schemata WHERE SCHEMA_NAME='" + name + "';", database='mysql', root=True).strip():
        raise ValueError('Database already exists; choose a new name')
    mysql(f"CREATE DATABASE `{name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin; GRANT ALL ON `{name}`.* TO 'feishu'@'%';",database='mysql',root=True)
    compose('stop', 'backend', 'frontend', stdout=subprocess.DEVNULL)
    current = config()
    current.update(FEISHU_MYSQL_DATABASE=name, FEISHU_APP_DATA_VOLUME=PROJECT+'_'+name+'_data', FEISHU_CLAUDE_HOME_VOLUME=PROJECT+'_'+name+'_home')
    temporary = ENV_FILE.with_suffix('.tmp')
    with temporary.open('w',encoding='utf-8') as out:
        for key,value in current.items(): out.write(key+'='+json.dumps(value,ensure_ascii=False)+'\n')
    temporary.chmod(0o600)
    temporary.replace(ENV_FILE)
    print('Selected new empty store:',name,'(old database and volumes retained). Restore/migrate before up.')


def stamp(): return dt.datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(3)


def row_counts():
    names = mysql('SHOW TABLES;').splitlines()
    return {name: int(mysql('SELECT COUNT(*) FROM `' + name.replace('`','``') + '`;').strip()) for name in names}

def backup(destination):
    # Stop the backend first: SQL transactions alone do not freeze the KB filesystem.
    compose('stop', 'backend', 'frontend', stdout=subprocess.DEVNULL)
    path = Path(destination or ROOT / 'backups' / ('feishu-' + stamp() + '.zip')).resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists(): raise ValueError('Backup output already exists; refusing to overwrite')
    settings = config()
    env = dict(os.environ, MYSQL_PWD=settings['FEISHU_MYSQL_PASSWORD'])
    with tempfile.TemporaryDirectory(prefix='feishu-backup-') as tmp:
        folder = Path(tmp)
        with (folder / 'database.sql').open('wb') as out:
            run(['docker', 'exec', '-e', 'MYSQL_PWD', CONTAINER, 'mysqldump', '-ufeishu', '--single-transaction', '--quick',
                 '--no-tablespaces', '--set-gtid-purged=OFF', '--hex-blob', '--default-character-set=utf8mb4', dbname()], env=env, stdout=out)
        # app_data also contains delivery snapshots; it is intentionally separate from SQL.
        with (folder / 'app-data.tar').open('wb') as out:
            compose('run', '--rm', '--no-deps', '--user', '0', '--entrypoint', 'tar', '-T', 'backend', '-C', '/app/data', '-cf', '-', '.', stdout=out)
        with (folder / 'claude-home.tar').open('wb') as out:
            compose('run', '--rm', '--no-deps', '--user', '0', '--entrypoint', 'tar', '-T', 'backend', '-C', '/home/node', '--exclude=./.claude/debug', '--exclude=./.cache', '--exclude=./.npm', '-cf', '-', '.', stdout=out)
        files = ['database.sql', 'app-data.tar', 'claude-home.tar']
        manifest = {'format': 1, 'created_at': dt.datetime.now(dt.timezone.utc).isoformat(), 'kb_root': '/app/data/supplier-kb',
                    'rows': row_counts(), 'files': {name: hashlib.sha256((folder / name).read_bytes()).hexdigest() for name in files}}
        (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        try:
            with zipfile.ZipFile(path, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
                for name in [*files, 'manifest.json']: archive.write(folder / name, name)
            path.chmod(0o600)
        except Exception:
            path.unlink(missing_ok=True)
            raise
    print('Backup:', path)
    print('Includes database, knowledge files and Claude home (credentials may be present). Backend stays stopped; use up to resume.')


def restore(source):
    compose('up', '-d', '--wait', 'mysql')
    if int(mysql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();').strip()):
        raise ValueError('Restore requires an EMPTY project database. Existing data will not be overwritten.')
    compose('stop', 'backend', 'frontend', stdout=subprocess.DEVNULL)
    with tempfile.TemporaryDirectory(prefix='feishu-restore-') as tmp:
        folder = Path(tmp)
        with zipfile.ZipFile(source) as archive:
            expected = {'manifest.json', 'database.sql', 'app-data.tar', 'claude-home.tar'}
            if len(archive.namelist()) != len(expected) or set(archive.namelist()) != expected: raise ValueError('Unexpected backup members')
            for name in expected:
                with archive.open(name) as inp, (folder / name).open('wb') as out: shutil.copyfileobj(inp, out)
        manifest = json.loads((folder / 'manifest.json').read_text())
        if manifest.get('format') != 1: raise ValueError('Unsupported backup format')
        import tarfile
        if set(manifest.get('files', {})) != expected - {'manifest.json'}: raise ValueError('Missing backup checksums')
        for name, digest in manifest['files'].items():
            if name not in expected - {'manifest.json'} or hashlib.sha256((folder / name).read_bytes()).hexdigest() != digest:
                raise ValueError('Backup checksum mismatch')
        for name in ['app-data.tar', 'claude-home.tar']:
            with tarfile.open(folder / name) as archive:
                for member in archive.getmembers():
                    if member.name.startswith('/') or '..' in Path(member.name).parts or member.issym() or member.islnk() or not (member.isdir() or member.isfile()):
                        raise ValueError('Unsafe backup path or link: ' + member.name)
        for name, target in [('app-data.tar', '/app/data'), ('claude-home.tar', '/home/node')]:
            with (folder / name).open('rb') as inp:
                compose('run', '--rm', '--no-deps', '--user', '0', '--entrypoint', 'tar', '-T', 'backend', '-C', target, '-xf', '-', stdin=inp, stdout=subprocess.DEVNULL)
        env = dict(os.environ, MYSQL_PWD=config()['FEISHU_MYSQL_PASSWORD'])
        with (folder / 'database.sql').open('rb') as inp:
            run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', CONTAINER, 'mysql', '-ufeishu', '--default-character-set=utf8mb4', dbname()], env=env, stdin=inp)
        compose('run', '--rm', '--no-deps', '--user', '0', '--entrypoint', 'sh', '-T', 'backend', '-c',
                'chown -R node:$(id -g node) /app/data /home/node', stdout=subprocess.DEVNULL)
    if row_counts() != manifest['rows']: raise ValueError('Restored table counts differ from backup')
    print('Restored into local MySQL and volumes. Container data paths remain unchanged across computers. Use up to start.')


def migrate_h2(source):
    file = Path(source).expanduser().resolve()
    if not file.is_file() or not file.name.endswith('.mv.db'): raise ValueError('Pass an existing config.mv.db file; source cannot be created automatically')
    compose('up', '-d', '--wait', 'mysql')
    if int(mysql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();').strip()):
        raise ValueError('Migration requires an EMPTY project database; do not start backend before migrating')
    compose('stop', 'backend', 'frontend', stdout=subprocess.DEVNULL)
    run(['docker', 'run', '--rm', '-v', str(ROOT) + ':/workspace', '-w', '/workspace',
         'maven:3.9-eclipse-temurin-21', 'mvn', '-q', '-s', 'scripts/maven-settings.xml', 'dependency:copy-dependencies',
         '-DincludeScope=runtime', '-DoutputDirectory=target/database-libs'])
    destination = ROOT / 'backups' / ('h2-migration-' + stamp())
    destination.mkdir(parents=True, mode=0o700)
    settings = config()
    env = dict(os.environ, SOURCE_URL='jdbc:h2:file:/source/' + file.name[:-6] + ';IFEXISTS=TRUE;ACCESS_MODE_DATA=r',
               SOURCE_FILE='/source/' + file.name, SOURCE_USER=os.environ.get('FEISHU_H2_USER', 'sa'), SOURCE_PASSWORD=os.environ.get('FEISHU_H2_PASSWORD', ''),
               TARGET_URL='jdbc:mysql://mysql:3306/' + dbname() + '?connectionTimeZone=UTC&characterEncoding=UTF-8',
               TARGET_USER='feishu', TARGET_PASSWORD=settings['FEISHU_MYSQL_PASSWORD'], REPORT_DIR='/backup')
    args = ['docker', 'run', '--rm', '--network', PROJECT + '_default', '-v', str(ROOT) + ':/workspace:ro',
            '-v', str(file.parent) + ':/source:ro', '-v', str(destination) + ':/backup']
    for key in ['SOURCE_URL', 'SOURCE_FILE', 'SOURCE_USER', 'SOURCE_PASSWORD', 'TARGET_URL', 'TARGET_USER', 'TARGET_PASSWORD', 'REPORT_DIR']: args += ['-e', key]
    run(args + ['maven:3.9-eclipse-temurin-21', 'java', '-cp', '/workspace/target/database-libs/*', '/workspace/scripts/H2ToMysql.java'], env=env)
    print('Migration verified. Original H2 retained; backup and table checksums:', destination)
    print('Copy legacy KB files into /app/data/supplier-kb before starting; see docs/MySQL本地部署与迁移.md for path mapping.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('init'); sub.add_parser('up'); sub.add_parser('down'); sub.add_parser('status')
    sub.add_parser('build')
    p = sub.add_parser('new-store'); p.add_argument('--name', required=True)
    p = sub.add_parser('backup'); p.add_argument('--output')
    p = sub.add_parser('restore'); p.add_argument('archive')
    p = sub.add_parser('migrate-h2'); p.add_argument('--source', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'init': init()
        elif args.command == 'up': config(); compose('up', '-d', '--build', '--wait')
        elif args.command == 'build': config(); compose('build')
        elif args.command == 'down': compose('down')  # never remove volumes
        elif args.command == 'status': compose('ps')
        elif args.command == 'new-store': new_store(args.name)
        elif args.command == 'backup': backup(args.output)
        elif args.command == 'restore': restore(Path(args.archive).resolve())
        elif args.command == 'migrate-h2': migrate_h2(args.source)
    except (ValueError, subprocess.CalledProcessError, OSError, zipfile.BadZipFile) as error:
        print('ERROR:', str(error) if not isinstance(error, subprocess.CalledProcessError) else 'Command failed; no success claimed. Inspect container logs.', file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
