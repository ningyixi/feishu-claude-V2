"""Real H2 -> isolated MySQL integration. Run after database.py init and mysql up."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
from scripts import database as db


class MigrationIntegrationTest(unittest.TestCase):
    def test_missing_source_never_creates_a_database(self):
        with self.assertRaises(ValueError): db.migrate_h2('/not-a-real-source/config.mv.db')

    def test_lossless_copy_and_identity_and_nonempty_refusal(self):
        schema = 'feishu_migration_test_' + secrets.token_hex(6)
        db.mysql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin; GRANT ALL ON `{schema}`.* TO 'feishu'@'%';", database='mysql', root=True)
        try:
            with tempfile.TemporaryDirectory(prefix='feishu-migration-test-') as tmp:
                source = Path(tmp); report = source / 'report'; report.mkdir()
                subprocess.run(['docker','run','--rm','-v',str(db.ROOT)+':/workspace:ro','-v',tmp+':/fixture', 'maven:3.9-eclipse-temurin-21',
                                'java','-cp','/workspace/target/database-libs/*','/workspace/scripts/tests/MigrationFixture.java','/fixture/config'],check=True)
                original = hashlib.sha256((source/'config.mv.db').read_bytes()).hexdigest()
                env = dict(os.environ, SOURCE_URL='jdbc:h2:file:/source/config;IFEXISTS=TRUE;ACCESS_MODE_DATA=r',SOURCE_FILE='/source/config.mv.db',
                           SOURCE_USER='sa',SOURCE_PASSWORD='',TARGET_URL=f'jdbc:mysql://mysql:3306/{schema}?connectionTimeZone=UTC',TARGET_USER='feishu',
                           TARGET_PASSWORD=db.config()['FEISHU_MYSQL_PASSWORD'], REPORT_DIR='/report')
                command=['docker','run','--rm','--network','feishu-claude_default','-v',str(db.ROOT)+':/workspace:ro','-v',tmp+':/source:ro','-v',str(report)+':/report']
                for name in ['SOURCE_URL','SOURCE_FILE','SOURCE_USER','SOURCE_PASSWORD','TARGET_URL','TARGET_USER','TARGET_PASSWORD','REPORT_DIR']: command += ['-e',name]
                command += ['maven:3.9-eclipse-temurin-21','java','-cp','/workspace/target/database-libs/*','/workspace/scripts/H2ToMysql.java']
                subprocess.run(command,env=env,check=True)
                result=json.loads((report/'migration-report.json').read_text())
                self.assertEqual(result['status'],'verified')
                self.assertEqual(result['tables']['parent']['rows'],3)
                self.assertEqual(result['tables']['child']['rows'],1)
                self.assertEqual(original,hashlib.sha256((source/'config.mv.db').read_bytes()).hexdigest())
                self.assertEqual(original,hashlib.sha256((report/'source.mv.db').read_bytes()).hexdigest())
                self.assertEqual(db.mysql('SELECT CHAR_LENGTH(note),HEX(payload),created_at,amount FROM parent WHERE id=0;',database=schema).strip(),
                                 '100000\t0001FF2A\t2026-11-03 12:34:56.123456\t123456.123456')
                self.assertEqual(db.mysql('SELECT COUNT(*) FROM parent WHERE name IN (\'case\',\'CASE\');',database=schema).strip(),'2')
                self.assertEqual(db.mysql("INSERT INTO parent(name) VALUES ('next ID'); SELECT LAST_INSERT_ID();",database=schema).strip(),'1001')
                refused=subprocess.run(command,env=env,capture_output=True)
                self.assertNotEqual(refused.returncode,0)
                self.assertEqual(db.mysql('SELECT COUNT(*) FROM parent;',database=schema).strip(),'4')
        finally:
            db.mysql(f'DROP DATABASE `{schema}`;',database='mysql',root=True)

if __name__=='__main__': unittest.main()
