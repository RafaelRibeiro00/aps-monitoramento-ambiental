"""Teste integrado Java + simuladores Python + SSE, sem Docker e com banco temporario."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
from urllib.request import Request, urlopen

RAIZ=Path(__file__).resolve().parent.parent
SERVICOS=[("manancial","manancial", "manancial.py", "leituras_manancial"),
          ("alagamento","alagamento", "alagamento.py", "leituras_alagamento"),
          ("inversao-termica","inversaotermica", "inversao_termica.py", "leituras_inversaotermica")]

def livre():
    with socket.socket() as s:
        s.bind(("127.0.0.1",0))
        return s.getsockname()[1]

def json_get(url):
    with urlopen(url,timeout=3) as resposta:
        return json.load(resposta)

def esperar(condicao):
    prazo=time.monotonic()+15
    while time.monotonic()<prazo:
        try:
            if condicao():
                return
        except (OSError, ValueError):
            pass
        time.sleep(0.1)
    raise AssertionError("A operacao nao concluiu em 15 segundos")

def evento(stream):
    for linha in stream:
        texto=linha.decode().strip()
        if texto.startswith("data: "):
            return json.loads(texto[6:])
    raise AssertionError("SSE encerrou sem enviar leitura")

def main():
    java=shutil.which("java")
    if not java:
        raise RuntimeError("Java 17 nao encontrado no PATH")
    with tempfile.TemporaryDirectory(prefix="aps-integracao-") as pasta:
        pasta=Path(pasta)
        assert pasta.resolve().parent==Path(tempfile.gettempdir()).resolve()
        banco=pasta/"aps.db"
        processos=[]
        arquivos=[]
        try:
            for nome,pacote,script,tabela in SERVICOS:
                target=RAIZ/"servicos"/nome/"target"
                if not (target/"dependency").is_dir():
                    raise RuntimeError("Execute Maven package e dependency:copy-dependencies antes deste teste.")
                porta=livre();base=f"http://127.0.0.1:{porta}"
                ambiente=dict(os.environ,APS_HOST="127.0.0.1",APS_PORT=str(porta),APS_DB=str(banco),ALERTA_WEBHOOK_URL="",ALERTA_WEBHOOK_TOKEN="")
                log=(pasta/(nome+".log")).open("w",encoding="utf-8");arquivos.append(log)
                processo=subprocess.Popen([java,"-Dfile.encoding=UTF-8","-cp",str(target/"classes")+os.pathsep+str(target/"dependency"/"*"),"br.edu.aps."+pacote+".Api"],cwd=RAIZ,env=ambiente,stdout=log,stderr=subprocess.STDOUT)
                processos.append(processo)
                esperar(lambda: json_get(base+"/health/ready").get("status")=="ok")
                prefixo=base+"/"+nome
                with urlopen(Request(prefixo+"/tempo-real",headers={"Accept":"text/event-stream"}),timeout=10) as stream, ThreadPoolExecutor(max_workers=1) as executor:
                    proximo=executor.submit(evento,stream)
                    cliente_env=dict(os.environ)
                    cliente_env[nome.upper().replace("-","_")+"_URL"]=prefixo+"/leituras"
                    cliente=subprocess.run([sys.executable,str(RAIZ/"PYTHON"/script),"--quantidade","2"],cwd=RAIZ,env=cliente_env,capture_output=True,text=True,timeout=20)
                    assert cliente.returncode==0,cliente.stdout+cliente.stderr
                    esperados=6 if nome=="alagamento" else 2
                    assert cliente.stdout.count("HTTP 202")==esperados,cliente.stdout
                    recebido=proximo.result(timeout=8)
                    assert "evento_id" in recebido,recebido
                esperar(lambda: json_get(prefixo+"/historico")["total"]==2)
                assert isinstance(json_get(prefixo+"/alertas")["alertas"],list)
                assert "total" in json_get(prefixo+"/alertas-historico?limite=1&ordem=desc")
                print(f"OK {nome}: {esperados} POSTs Python aceitos, SSE recebido e 2 leituras no historico.",flush=True)
            with closing(sqlite3.connect(banco)) as c:
                assert c.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
                assert c.execute("SELECT count(*) FROM medicoes_alagamento").fetchone()[0]==6
            print("Integracao aprovada: 10 POSTs, 6 leituras consolidadas, 3 streams SSE. Banco real nao alterado.")
        except Exception:
            for arquivo in arquivos:
                arquivo.flush()
            for log in pasta.glob("*.log"):
                print(log.name,log.read_text(encoding="utf-8",errors="replace")[-4000:],file=sys.stderr)
            raise
        finally:
            for processo in processos:
                if processo.poll() is None:
                    processo.terminate()
            for processo in processos:
                try:
                    processo.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    processo.kill();processo.wait()
            for arquivo in arquivos:
                arquivo.close()

if __name__=="__main__":
    main()
