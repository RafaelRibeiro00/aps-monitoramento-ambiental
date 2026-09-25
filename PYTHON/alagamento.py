"""Tres sensores independentes do mesmo ponto; cada medicao usa seu proprio POST."""
import argparse
from datetime import datetime
import json
import random
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from configuracao import obter, url_api

URL_API = url_api("ALAGAMENTO", 8082)
PONTO_ID = obter("ALAGAMENTO_PONTO_ID", "PONTO-001")
INTERVALO_SEGUNDOS = 1.0
SENSORES = {
    "nivel_corrego": ("NIVEL-001", "cm", 0, 500),
    "chuva": ("CHUVA-001", "mm", 0, 100),
    "velocidade_agua": ("VELOCIDADE-001", "m/s", 0, 5),
}

def gerar_medicao(tipo):
    sensor, unidade, minimo, maximo = SENSORES[tipo]
    return {"sensor_id": sensor, "ponto_id": PONTO_ID, "tipo": tipo,
            "valor": round(random.uniform(minimo, maximo), 2), "unidade": unidade,
            "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds")}

def enviar_medicao(medicao):
    pedido = Request(URL_API, data=json.dumps(medicao, allow_nan=False).encode(),
                     headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urlopen(pedido, timeout=5) as resposta:
            print(f"Sensor {medicao['sensor_id']} | {medicao['valor']} {medicao['unidade']} | HTTP {resposta.status} | {resposta.read().decode()}", flush=True)
            return resposta.status == 202
    except HTTPError as erro:
        with erro:
            print(f"Medicao rejeitada: HTTP {erro.code} | {erro.read().decode()}", flush=True)
    except (URLError, TimeoutError, OSError) as erro:
        print(f"Envio sem confirmacao: {erro}", flush=True)
    return False

def executar(quantidade=None, sensor="todos", intervalo=INTERVALO_SEGUNDOS):
    if intervalo <= 0 or not __import__("math").isfinite(intervalo):
        raise ValueError("Intervalo deve ser positivo e finito.")
    if quantidade is not None and quantidade < 1:
        raise ValueError("Quantidade deve ser positiva.")
    tipos = list(SENSORES) if sensor == "todos" else [sensor]
    tentativas = aceitas = ciclos = 0
    print(f"Alagamento | ponto={PONTO_ID} | destino={URL_API} | sensores={', '.join(tipos)}", flush=True)
    print("Cada sensor envia um POST. A API junta os valores em ate 3 segundos. Ctrl+C encerra.", flush=True)
    try:
        while quantidade is None or ciclos < quantidade:
            inicio = time.monotonic()
            for tipo in tipos:
                tentativas += 1
                aceitas += enviar_medicao(gerar_medicao(tipo))
            ciclos += 1
            if quantidade is None or ciclos < quantidade:
                time.sleep(max(0, intervalo - (time.monotonic() - inicio)))
    except KeyboardInterrupt:
        print("Simulador encerrado.")
    print(f"Resumo: {tentativas} POSTs | {aceitas} aceitos | {tentativas-aceitas} sem confirmacao.")
    return tentativas, aceitas

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Simula sensores individuais de alagamento.")
    parser.add_argument("--sensor", choices=["todos", *SENSORES], default="todos")
    parser.add_argument("--quantidade", type=int, help="Numero de ciclos; todos envia 3 POSTs por ciclo.")
    parser.add_argument("--intervalo", type=float, default=INTERVALO_SEGUNDOS, help="Segundos entre ciclos.")
    args = parser.parse_args()
    try:
        total, aceitas = executar(args.quantidade, args.sensor, args.intervalo)
        raise SystemExit(0 if total == aceitas else 1)
    except ValueError as erro:
        parser.error(str(erro))
