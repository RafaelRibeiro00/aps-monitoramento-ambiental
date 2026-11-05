import unittest
import sys
from pathlib import Path
import tempfile
import sqlite3
from contextlib import closing
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "PYTHON"))
import alagamento
from limpar_banco import limpar_banco
from migrar_banco import migrar

class PythonTest(unittest.TestCase):
    def test_sensores_individuais(self):
        for tipo, dados in alagamento.SENSORES.items():
            leitura = alagamento.gerar_medicao(tipo)
            self.assertEqual(set(leitura), {"sensor_id", "ponto_id", "tipo", "valor", "unidade", "timestamp"})
            self.assertEqual(leitura["tipo"], tipo)
            self.assertEqual(leitura["unidade"], dados[1])
            self.assertGreaterEqual(leitura["valor"], 0)

    def test_migracao_preserva_e_limpeza_remove_alertas(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(Path(tmp).resolve().parent, Path(tempfile.gettempdir()).resolve())
            banco=Path(tmp)/"teste.db"
            with closing(sqlite3.connect(banco)) as c, c:
                for servico in ("manancial", "alagamento", "inversaotermica"):
                    extra = ", sensor TEXT, nivel_corrego_cm REAL NOT NULL, chuva_mm REAL, velocidade_agua_m_s REAL" if servico=="alagamento" else ""
                    c.execute(f"CREATE TABLE leituras_{servico}(id INTEGER PRIMARY KEY AUTOINCREMENT{extra})")
                    c.execute(f"INSERT INTO leituras_{servico} VALUES (1" + (", 'P1', 1, 2, 3)" if extra else ")"))
                    c.execute(f"CREATE TABLE alertas_{servico}(id INTEGER PRIMARY KEY AUTOINCREMENT, leitura_id INTEGER NOT NULL UNIQUE REFERENCES leituras_{servico}(id), status_notificacao TEXT, proxima_tentativa INTEGER)")
                    c.execute(f"INSERT INTO alertas_{servico} VALUES (1,1,'pendente',0)")
            migrar(banco);migrar(banco)
            with closing(sqlite3.connect(banco)) as c:
                self.assertEqual(c.execute("SELECT nivel_corrego_cm,chuva_mm,velocidade_agua_m_s FROM leituras_alagamento").fetchone(),(1,2,3))
                self.assertEqual(c.execute("PRAGMA foreign_key_check").fetchall(),[])
                c.execute("INSERT INTO medicoes_alagamento VALUES ('e','s','p','chuva',1,'mm','2026-09-24T00:00:00Z','2026-09-24T00:00:00Z',NULL)")
                c.commit()
            self.assertEqual(sum(limpar_banco(banco).values()),7)
            self.assertEqual(sum(limpar_banco(banco).values()),0)

if __name__ == '__main__':
    unittest.main()
