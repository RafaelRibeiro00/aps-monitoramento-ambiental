"""Migra bancos existentes sem apagar leituras. Pare as APIs antes de executar."""
from pathlib import Path
from contextlib import closing
from datetime import datetime
import sqlite3
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "PYTHON"))
from configuracao import banco_docker

SERVICOS = ("manancial", "alagamento", "inversaotermica")

def migrar(arquivo):
    arquivo = Path(arquivo).resolve()
    if not arquivo.is_file():
        raise FileNotFoundError(arquivo)
    pasta = arquivo.parent / "backups"
    pasta.mkdir(exist_ok=True)
    backup = pasta / ("antes-sse-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".db")
    with closing(sqlite3.connect(arquivo.as_uri() + "?mode=rw", uri=True, timeout=10)) as c:
        with closing(sqlite3.connect(backup)) as destino:
            c.backup(destino)
        c.execute("PRAGMA foreign_keys=OFF")
        c.execute("BEGIN IMMEDIATE")
        try:
            for servico in SERVICOS:
                leituras = "leituras_" + servico
                alertas = "alertas_" + servico
                for tabela, antigo, novo in [(leituras, "nivel_corrego_cm REAL NOT NULL", "nivel_corrego_cm REAL")] if servico == "alagamento" else []:
                    reconstruir(c, tabela, antigo, novo)
                reconstruir(c, alertas, "leitura_id INTEGER NOT NULL UNIQUE", "leitura_id INTEGER")
                for tabela in (leituras, alertas):
                    if not existe(c, tabela):
                        continue
                    adicionar(c, tabela, "evento_id", "TEXT")
                    c.execute(f"CREATE UNIQUE INDEX IF NOT EXISTS idx_{tabela}_evento ON {tabela}(evento_id)")
                if existe(c, alertas):
                    c.execute(f"CREATE INDEX IF NOT EXISTS idx_{alertas}_envio ON {alertas}(status_notificacao,proxima_tentativa)")
            if existe(c, "leituras_alagamento"):
                adicionar(c, "leituras_alagamento", "ponto_id", "TEXT")
                adicionar(c, "leituras_alagamento", "status", "TEXT")
                c.execute("UPDATE leituras_alagamento SET ponto_id=sensor WHERE ponto_id IS NULL")
                c.execute("UPDATE leituras_alagamento SET status=CASE WHEN nivel_corrego_cm IS NULL OR chuva_mm IS NULL OR velocidade_agua_m_s IS NULL THEN 'incompleta' ELSE 'completa' END WHERE status IS NULL")
                c.execute("CREATE TABLE IF NOT EXISTS medicoes_alagamento (evento_id TEXT PRIMARY KEY, sensor_id TEXT NOT NULL, ponto_id TEXT NOT NULL, tipo TEXT NOT NULL, valor REAL NOT NULL, unidade TEXT NOT NULL, timestamp TEXT NOT NULL, recebido_em TEXT NOT NULL, conjunto_id TEXT)")
                c.execute("CREATE INDEX IF NOT EXISTS idx_medicoes_ponto ON medicoes_alagamento(ponto_id,timestamp)")
            if c.execute("PRAGMA foreign_key_check").fetchone():
                raise RuntimeError("Referencias invalidas; migracao cancelada.")
            if c.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("Falha de integridade; migracao cancelada.")
            c.commit()
        except Exception:
            c.rollback()
            raise
    return backup

def existe(c, tabela):
    return c.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (tabela,)).fetchone() is not None

def adicionar(c, tabela, coluna, tipo):
    if coluna not in {r[1] for r in c.execute(f"PRAGMA table_info({tabela})")}:
        c.execute(f"ALTER TABLE {tabela} ADD COLUMN {coluna} {tipo}")

def reconstruir(c, tabela, antigo, novo):
    linha = c.execute("SELECT sql FROM sqlite_master WHERE name=?", (tabela,)).fetchone()
    if not linha or antigo not in linha[0]:
        return
    colunas = [r[1] for r in c.execute(f"PRAGMA table_info({tabela})")]
    registros = c.execute(f"SELECT * FROM {tabela} ORDER BY id").fetchall()
    sequencia = c.execute("SELECT seq FROM sqlite_sequence WHERE name=?", (tabela,)).fetchone()
    sql = linha[0].replace(antigo, novo).replace(tabela, tabela + "_migracao", 1)
    c.execute(sql)
    c.execute(f"INSERT INTO {tabela}_migracao SELECT * FROM {tabela}")
    c.execute(f"DROP TABLE {tabela}")
    c.execute(f"ALTER TABLE {tabela}_migracao RENAME TO {tabela}")
    if sequencia:
        c.execute("UPDATE sqlite_sequence SET seq=max(seq,?) WHERE name=?", (sequencia[0], tabela))
    assert c.execute(f"SELECT {','.join(colunas)} FROM {tabela} ORDER BY id").fetchall() == registros

if __name__ == "__main__":
    arquivo = Path(sys.argv[1]) if len(sys.argv) > 1 else banco_docker()
    backup = migrar(arquivo)
    print(f"Banco migrado: {arquivo}")
    print(f"Dados preservados. Backup: {backup}")
