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
from urllib.error import HTTPError

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


def pedido(url, metodo="GET", corpo=None, esperado=200):
    dados=None if corpo is None else json.dumps(corpo).encode()
    req=Request(url,data=dados,method=metodo,headers={"Content-Type":"application/json"})
    try:
        resposta=urlopen(req,timeout=8)
    except HTTPError as erro:
        resposta=erro
    with resposta:
        texto=resposta.read().decode()
        assert resposta.code==esperado,(url,resposta.code,esperado,texto)
        return json.loads(texto)

def validar_rotas(base,nome,banco,tabela):
    prefixo=base+"/"+nome
    for rota in ("/health/live","/health/ready"):
        assert pedido(base+rota)["status"]=="ok"
    pedido(prefixo+"/leituras",esperado=405)
    pedido(prefixo+"/leituras","POST",{},400)
    pedido(prefixo+"/inexistente",esperado=404)
    for rota in ("/historico","/tempo-real","/alertas","/alertas-historico"):
        pedido(prefixo+rota,"POST",{},405)
    pedido(prefixo+"/historico?filtro_inexistente=1",esperado=400)
    pedido(prefixo+"/alertas?limite=1",esperado=400)
    stamp="2026-09-25T15:00:00Z"
    if nome=="manancial":
        corpos=[dict(area="VALIDACAO",timestamp=stamp,percentual_ocupado=10,nivel_agua_m=2,temperatura_agua_c=20)]
        filtro="area=VALIDACAO";medida="percentual_ocupado";valor=10
    elif nome=="inversao-termica":
        corpos=[dict(estacao="VALIDACAO",timestamp=stamp,umidade=20,temperatura_c=35,vento_km_h=2)]
        filtro="estacao=VALIDACAO";medida="temperatura_c";valor=35
    else:
        corpos=[dict(sensor_id=tipo+"-TESTE",ponto_id="VALIDACAO",tipo=tipo,valor=v,unidade=u,timestamp=f"2026-09-25T15:00:0{i}Z")
                for i,(tipo,v,u) in enumerate((("nivel_corrego",210,"cm"),("chuva",20,"mm"),("velocidade_agua",2,"m/s")))]
        filtro="ponto_id=VALIDACAO";medida="nivel_corrego_cm";valor=210
    with urlopen(prefixo+"/tempo-real",timeout=10) as stream, ThreadPoolExecutor(max_workers=1) as executor:
        futuro=executor.submit(evento,stream)
        for corpo in corpos:
            pedido(prefixo+"/leituras","POST",corpo,202)
        recebido=futuro.result(timeout=8)
        assert recebido[medida]==valor,recebido
        if nome=="alagamento":
            assert recebido["status"]=="completa" and recebido["timestamp"]==corpos[-1]["timestamp"],recebido
    consulta=prefixo+"/historico?"+filtro
    esperar(lambda: json_get(consulta)["total"]==1)
    leitura=pedido(consulta)["leituras"][0]
    assert leitura[medida]==valor
    assert leitura["evento_id"]==recebido["evento_id"]
    assert pedido(consulta+"&data=2026-09-25&"+medida+"_min="+str(valor))["total"]==1
    assert pedido(consulta+"&"+medida+"_min="+str(valor+1))["total"]==0
    assert len(pedido(prefixo+"/historico?limite=1&offset=1&ordem=desc")["leituras"])==1
    assert pedido(prefixo+"/alertas")["alertas"],nome
    alerta_url=prefixo+"/alertas-historico?"+filtro+"&limite=1&ordem=desc"
    esperar(lambda: json_get(alerta_url)["total"]>=1)
    assert pedido(alerta_url)["alertas"]
    with closing(sqlite3.connect(banco)) as c:
        row=c.execute("SELECT "+medida+" FROM "+tabela+" WHERE evento_id=?",(recebido["evento_id"],)).fetchone()
        assert row==(valor,),row
    if nome=="alagamento":
        corpo=dict(corpos[0],ponto_id="INCOMPLETO",valor=5)
        with urlopen(prefixo+"/tempo-real",timeout=10) as stream, ThreadPoolExecutor(max_workers=1) as executor:
            futuro=executor.submit(evento,stream)
            inicio=time.monotonic()
            pedido(prefixo+"/leituras","POST",corpo,202)
            pedido(prefixo+"/leituras","POST",corpo,409)
            incompleto=futuro.result(timeout=8)
            assert 2.5<=time.monotonic()-inicio<8
            assert incompleto["status"]=="incompleta" and incompleto["chuva_mm"] is None and incompleto["velocidade_agua_m_s"] is None,incompleto
        esperar(lambda: json_get(prefixo+"/historico?ponto_id=INCOMPLETO")["total"]==1)
        row=pedido(prefixo+"/historico?ponto_id=INCOMPLETO")["leituras"][0]
        assert row["chuva_mm"] is None and row["velocidade_agua_m_s"] is None
    print(f"OK {nome}: todas as rotas, filtros, POST invalido, metodos, alerta recente/historico e igualdade SSE/SQLite.",flush=True)

def main(docker=False):
    containers=[]
    docker_cli=os.environ.get("DOCKER_CLI", "docker")
    java=shutil.which("java")
    if not java and not docker:
        raise RuntimeError("Java 17 nao encontrado no PATH")
    with tempfile.TemporaryDirectory(prefix="aps-integracao-") as pasta:
        pasta=Path(pasta)
        assert pasta.resolve().parent==Path(tempfile.gettempdir()).resolve()
        banco=pasta/"aps.db"
        processos=[]
        arquivos=[]
        bases=[]
        try:
            for nome,pacote,script,tabela in SERVICOS:
                target=RAIZ/"servicos"/nome/"target"
                if not docker and not (target/"dependency").is_dir():
                    raise RuntimeError("Execute Maven package e dependency:copy-dependencies antes deste teste.")
                porta=livre();base=f"http://127.0.0.1:{porta}"
                bases.append(base)
                ambiente=dict(os.environ,APS_HOST="127.0.0.1",APS_PORT=str(porta),APS_DB=str(banco),ALERTA_WEBHOOK_URL="",ALERTA_WEBHOOK_TOKEN="")
                ambiente.update(ALERTA_PERCENTUAL_BAIXO="20",ALERTA_NIVEL_CORREGO_CM="200",ALERTA_CHUVA_MM="50",ALERTA_TEMPERATURA_C="30",ALERTA_UMIDADE_MAX="30",ALERTA_VENTO_MAX_KM_H="5")
                for chave in ("MANANCIAL","ALAGAMENTO","INVERSAO_TERMICA"):
                    ambiente[chave+"_WEBHOOK_URL"]=""
                    ambiente[chave+"_WEBHOOK_TOKEN"]=""
                log=(pasta/(nome+".log")).open("w",encoding="utf-8");arquivos.append(log)
                if docker:
                    container="aps-validacao-"+nome+"-"+str(porta)
                    containers.append(container)
                    comando=[docker_cli,"run","--rm","--name",container,"-p",f"127.0.0.1:{porta}:{porta}","--mount",f"type=bind,source={pasta},target=/app/dados"]
                    for chave,valor in ambiente.items():
                        if chave in ("APS_PORT",) or chave.startswith("ALERTA_"):
                            comando.extend(["-e",chave+"="+valor])
                    comando.extend(["-e","APS_HOST=0.0.0.0","-e","APS_DB=/app/dados/aps.db","aps-"+nome+":"+os.environ.get("APS_TEST_IMAGE_TAG","3.0.0")])
                else:
                    comando=[java,"-Dfile.encoding=UTF-8","-cp",str(target/"classes")+os.pathsep+str(target/"dependency"/"*"),"br.edu.aps."+pacote+".Api"]
                processo=subprocess.Popen(comando,cwd=RAIZ,env=ambiente,stdout=log,stderr=subprocess.STDOUT)
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
                validar_rotas(base,nome,banco,tabela)
            with closing(sqlite3.connect(banco)) as c:
                assert c.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
                assert c.execute("SELECT count(*) FROM medicoes_alagamento").fetchone()[0]==10
            if docker:
                for container,base,(nome,_,_,_) in zip(containers,bases,SERVICOS):
                    subprocess.run([docker_cli,"restart",container],check=True,capture_output=True)
                    esperar(lambda: json_get(base+"/health/ready").get("status")=="ok")
                    assert json_get(base+"/"+nome+"/historico")["total"]==(4 if nome=="alagamento" else 3)
                print("OK Docker: leituras preservadas apos restart dos tres containers.",flush=True)
            print("Integracao aprovada: 16 POSTs aceitos, 10 leituras consolidadas, SSE, alertas e filtros nas tres APIs. Banco real nao alterado.")
        except Exception:
            for arquivo in arquivos:
                arquivo.flush()
            for log in pasta.glob("*.log"):
                print(log.name,log.read_text(encoding="utf-8",errors="replace")[-4000:],file=sys.stderr)
            raise
        finally:
            for container in containers:
                subprocess.run([docker_cli,"rm","-f",container],capture_output=True,timeout=30)
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
