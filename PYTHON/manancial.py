from configuracao import obter, url_api

REQUISICOES_POR_SEGUNDO = 1  # 1 = uma por segundo; 2 = duas por segundo.
URL_API = url_api("MANANCIAL", 8081)
IDENTIFICADOR = obter("APS_IDENTIFICADOR", "AREA-001")
TEMPO_LIMITE_SEGUNDOS = 5

import argparse
from datetime import datetime
import json
import math
import random
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def gerar_leitura():
    """Gera tres novas medicoes simuladas para o mesmo sensor a cada envio."""
    return {
        "area": IDENTIFICADOR,
        "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
        "percentual_ocupado": round(random.uniform(0, 100), 2),
        "nivel_agua_m": round(random.uniform(0, 30), 2),
        "temperatura_agua_c": round(random.uniform(5, 35), 2),
    }


def enviar_leitura(leitura):
    corpo = json.dumps(leitura, allow_nan=False).encode("utf-8")
    pedido = Request(URL_API, data=corpo, method="POST",
                     headers={"Content-Type": "application/json"})
    try:
        with urlopen(pedido, timeout=TEMPO_LIMITE_SEGUNDOS) as resposta:
            mensagem = resposta.read().decode("utf-8", errors="replace")
            print(f"Leitura recebida pela API (gravacao agendada) | HTTP {resposta.status} | {corpo.decode('utf-8')} | {mensagem}", flush=True)
            return resposta.status == 202
    except HTTPError as erro:
        with erro:
            print(f"HTTP {erro.code}: {erro.read().decode('utf-8', errors='replace')}", flush=True)
    except (URLError, TimeoutError, OSError) as erro:
        print(f"Falha ao enviar para {URL_API}: {erro}. Confira se a API esta iniciada.", flush=True)
    return False


def executar(quantidade=None):
    taxa = REQUISICOES_POR_SEGUNDO
    if isinstance(taxa, bool) or not isinstance(taxa, (int, float)) or not math.isfinite(taxa) or taxa <= 0:
        raise ValueError("REQUISICOES_POR_SEGUNDO deve ser um numero finito maior que zero.")
    if quantidade is not None and (not isinstance(quantidade, int) or quantidade <= 0):
        raise ValueError("A quantidade deve ser um inteiro maior que zero.")
    intervalo = 1.0 / taxa
    tentativas = sucessos = 0
    proximo_envio = time.monotonic()
    print(f"Enviando para {URL_API} | {taxa} requisicoes/s | Ctrl+C para parar.", flush=True)
    try:
        while quantidade is None or tentativas < quantidade:
            time.sleep(max(0.0, proximo_envio - time.monotonic()))
            tentativas += 1
            if enviar_leitura(gerar_leitura()):
                sucessos += 1
            proximo_envio += intervalo
            agora = time.monotonic()
            if proximo_envio < agora:
                # Pula horarios perdidos: nao acumula envios nem dispara rajadas.
                perdidos = math.floor((agora - proximo_envio) / intervalo) + 1
                proximo_envio += perdidos * intervalo
                print("API lenta: taxa efetiva menor que a configurada.", flush=True)
    except KeyboardInterrupt:
        print("\nGerador encerrado.", flush=True)
    finally:
        print(f"Tentativas: {tentativas} | Aceitas: {sucessos} | Sem confirmacao: {tentativas - sucessos}", flush=True)
    return tentativas, sucessos


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Gera leituras aleatorias para a API manancial.")
    parser.add_argument("--quantidade", type=int, help="Limita o total de envios; omitido = continuo.")
    args = parser.parse_args()
    try:
        executar(args.quantidade)
    except ValueError as erro:
        parser.error(str(erro))
