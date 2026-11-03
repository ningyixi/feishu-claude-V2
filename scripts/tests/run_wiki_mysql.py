#!/usr/bin/env python3
"""Run the Wiki integration suite against an isolated MySQL database in OrbStack."""
import os
from pathlib import Path
import secrets
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from scripts import database as db
schema='feishu_wiki_it_'+secrets.token_hex(5)
db.mysql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin; GRANT ALL ON `{schema}`.* TO 'feishu'@'%';",database='mysql',root=True)
env=dict(os.environ,TEST_MYSQL_URL=f'jdbc:mysql://mysql:3306/{schema}?connectionTimeZone=UTC&characterEncoding=UTF-8',TEST_MYSQL_USER='feishu',TEST_MYSQL_PASSWORD=db.config()['FEISHU_MYSQL_PASSWORD'],TEST_KB_DIR='/workspace/target/'+schema)
command=['docker','run','--rm','--network','feishu-claude_default','-v',str(db.ROOT)+':/workspace','-v',str(Path.home()/'.m2')+':/root/.m2','-w','/workspace']
for key in ['TEST_MYSQL_URL','TEST_MYSQL_USER','TEST_MYSQL_PASSWORD','TEST_KB_DIR']:command+=['-e',key]
command+=['maven:3.9-eclipse-temurin-21','mvn','-q','-s','scripts/maven-settings.xml','-Dtest='+os.environ.get('FEISHU_IT_TEST','SupplierWikiMysqlIT'),'test']
try: result=subprocess.run(command,env=env)
finally:db.mysql(f"REVOKE ALL ON `{schema}`.* FROM 'feishu'@'%'; DROP DATABASE `{schema}`;",database='mysql',root=True)
sys.exit(result.returncode)
