"""Configuracao compartilhada com o Docker Compose, sem dependencias externas."""
import os
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent

def obter(nome, padrao):
    valores = {}
    arquivo = RAIZ / ".env"
    if arquivo.is_file():
        for linha in arquivo.read_text(encoding="utf-8-sig").splitlines():
            linha = linha.strip()
            if linha and not linha.startswith("#") and "=" in linha:
                chave, valor = linha.split("=", 1)
                valores[chave.strip()] = valor.strip().strip("\"'")
    return os.environ.get(nome, valores.get(nome, padrao))

def url_api(servico, porta):
    host = obter("APS_CLIENT_HOST", "127.0.0.1")
    return obter(servico + "_URL", f"http://{host}:{obter(servico + '_PORT', str(porta))}/leituras")

def banco_docker():
    pasta = Path(obter("APS_DATA_DIR", "./dados")).expanduser()
    if not pasta.is_absolute():
        pasta = RAIZ / pasta
    return pasta / obter("APS_DB_FILE", "aps.db")
