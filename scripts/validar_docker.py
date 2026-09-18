"""Valida a pilha real; insere tres leituras por servico e reinicia os containers.
Nao apaga leituras. Execute na raiz: python scripts/validar_docker.py
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
from datetime import datetime

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ / "PYTHON"))
from configuracao import banco_docker, obter

DOCKER = shutil.which("docker")
if not DOCKER:
    candidato = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs/DockerDesktop/resources/bin/docker.exe"
    if candidato.is_file():
        DOCKER = str(candidato)
if not DOCKER:
    raise SystemExit("Docker nao encontrado no PATH.")
SERVICOS = [
    ("manancial", 8081, "leituras_manancial", "area", "manancial.py"),
    ("alagamento", 8082, "leituras_alagamento", "sensor", "alagamento.py"),
    ("inversao-termica", 8083, "leituras_inversaotermica", "estacao", "inversao_termica.py"),
]

def docker(*args):
    resultado = subprocess.run([DOCKER, *args], cwd=RAIZ, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if resultado.returncode:
        raise RuntimeError(resultado.stdout)
    return resultado.stdout.strip()

def impressao(conexao, tabela, limite):
    linhas = conexao.execute(f"SELECT * FROM {tabela} WHERE id <= ? ORDER BY id", (limite,)).fetchall()
    return hashlib.sha256(json.dumps(linhas, ensure_ascii=False).encode()).hexdigest()

def main():
    banco = banco_docker().resolve()
    if not banco.is_file():
        raise RuntimeError(f"Banco nao encontrado: {banco}")
    marcador = "VALIDACAO-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    resultado = {"data": datetime.now().astimezone().isoformat(), "banco": str(banco),
                 "marcador": marcador, "servicos": {}}
    with sqlite3.connect(banco.as_uri() + "?mode=rw", uri=True, timeout=10) as conexao:
        anteriores = {}
        for nome, porta, tabela, campo, script in SERVICOS:
            limite = conexao.execute(f"SELECT coalesce(max(id), 0) FROM {tabela}").fetchone()[0]
            anteriores[tabela] = (limite, impressao(conexao, tabela, limite))
            estado = json.loads(docker("inspect", "cont-" + nome))[0]
            assert estado["State"]["Running"], nome
            assert estado["State"]["Health"]["Status"] == "healthy", nome
            assert estado["Config"]["Image"] == "aps-" + nome + ":" + obter("APS_IMAGE_TAG", "1.1.0")
            uid = docker("compose", "exec", "-T", nome, "id", "-u")
            assert uid != "0", nome
            docker("compose", "exec", "-T", nome, "sh", "-c",
                   "! command -v javac && ! command -v mvn && test ! -d /build && test -z \"$(find /app -name '*.java' -print -quit)\"")
            for rota in ("live", "ready"):
                resposta = docker("compose", "exec", "-T", nome, "curl", "-fsS", "--max-time", "3",
                                  f"http://127.0.0.1:{porta}/health/{rota}")
                assert json.loads(resposta)["status"] == "ok"
            ambiente = dict(os.environ, APS_IDENTIFICADOR=marcador)
            cliente = subprocess.run([sys.executable, str(RAIZ / "PYTHON" / script), "--quantidade", "3"],
                                     cwd=RAIZ, env=ambiente, text=True, capture_output=True, timeout=35)
            assert cliente.returncode == 0 and cliente.stdout.count("HTTP 200 |") == 3, cliente.stdout + cliente.stderr
            gravados = conexao.execute(f"SELECT count(*) FROM {tabela} WHERE {campo}=?", (marcador,)).fetchone()[0]
            assert gravados == 3, (nome, gravados)
            resultado["servicos"][nome] = {"uid": uid, "cliente_http_200": gravados, "saude": "live=200, ready=200"}
            print(f"OK {nome}: usuario {uid}, saude e 3 leituras gravadas.", flush=True)
        # Cada origem acessa outra API pelo nome do servico na rede do Compose.
        for indice, (nome, *_) in enumerate(SERVICOS):
            destino, porta, *_ = SERVICOS[(indice + 1) % len(SERVICOS)]
            resposta = docker("compose", "exec", "-T", nome, "curl", "-fsS", "--max-time", "3",
                              f"http://{destino}:{porta}/health/ready")
            assert json.loads(resposta)["status"] == "ok"
            resultado["servicos"][nome]["dns_destino"] = destino
        print("OK comunicacao por nomes entre os tres servicos.", flush=True)
        docker("compose", "restart")
        docker("compose", "up", "-d", "--wait", "--wait-timeout", "90")
        for nome, porta, tabela, campo, script in SERVICOS:
            limite, antes = anteriores[tabela]
            assert impressao(conexao, tabela, limite) == antes, f"Dados anteriores alterados: {tabela}"
            quantidade = conexao.execute(f"SELECT count(*) FROM {tabela} WHERE {campo}=?", (marcador,)).fetchone()[0]
            assert quantidade == 3, nome
            resultado["servicos"][nome]["persistencia_apos_restart"] = quantidade
            resultado["servicos"][nome]["dados_anteriores_preservados"] = True
    destino = RAIZ / "validacao-docker.json"
    destino.write_text(json.dumps(resultado, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("OK persistencia: as 9 leituras e todos os dados anteriores sobreviveram ao restart.")
    print(f"Resultado: {destino}")

if __name__ == "__main__":
    main()
