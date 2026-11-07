"""Carga simultânea nas três APIs do Sistema de Monitoramento Ambiental."""

import argparse
import concurrent.futures
import csv
import datetime as dt
import ipaddress
import json
import math
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path


SERVICOS = {
    "manancial": "/manancial/leituras",
    "alagamento": "/alagamento/leituras",
    "inversao-termica": "/inversao-termica/leituras",
}

CONTEXTO_KUBERNETES = "k3d-aps"
NAMESPACE_KUBERNETES = "aps"


def preparar_duas_replicas():
    kubectl = shutil.which("kubectl")
    if not kubectl:
        raise RuntimeError("O comando kubectl não foi encontrado no PATH.")

    print("Preparando duas réplicas de cada API...", flush=True)
    for servico in SERVICOS:
        subprocess.run(
            [
                kubectl,
                "--context",
                CONTEXTO_KUBERNETES,
                "-n",
                NAMESPACE_KUBERNETES,
                "scale",
                f"deployment/{servico}",
                "--replicas=2",
            ],
            check=True,
        )

    for servico in SERVICOS:
        subprocess.run(
            [
                kubectl,
                "--context",
                CONTEXTO_KUBERNETES,
                "-n",
                NAMESPACE_KUBERNETES,
                "rollout",
                "status",
                f"deployment/{servico}",
                "--timeout=180s",
            ],
            check=True,
        )

    print("\nPods disponíveis antes do teste:", flush=True)
    subprocess.run(
        [
            kubectl,
            "--context",
            CONTEXTO_KUBERNETES,
            "-n",
            NAMESPACE_KUBERNETES,
            "get",
            "pods",
            "-l",
            "camada=atendimento",
            "-o",
            "wide",
        ],
        check=True,
    )


def agora():
    return dt.datetime.now().astimezone().isoformat(timespec="seconds")


def percentil(valores, percentual):
    if not valores:
        return 0.0
    ordenados = sorted(valores)
    indice = max(0, math.ceil(len(ordenados) * percentual / 100) - 1)
    return ordenados[indice]


def obter_base_local(url):
    partes = urllib.parse.urlsplit(url)
    if partes.scheme != "http" or not partes.hostname or partes.username:
        raise ValueError("Use uma URL HTTP local e sem credenciais.")
    enderecos = socket.getaddrinfo(partes.hostname, partes.port or 80)
    if not all(ipaddress.ip_address(item[4][0]).is_loopback for item in enderecos):
        raise ValueError("Por segurança, o gerador aceita somente localhost/127.0.0.1.")
    return f"{partes.scheme}://{partes.netloc}"


def corpo_leitura(servico, trabalhador, sequencia, timestamp_grupo=None):
    timestamp = timestamp_grupo or dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")
    identificador = f"CARGA-{servico}-{trabalhador}-{sequencia}"

    if servico == "manancial":
        return {
            "area": identificador,
            "timestamp": timestamp,
            "percentual_ocupado": 40 + sequencia % 40,
            "nivel_agua_m": 2 + sequencia % 10,
            "temperatura_agua_c": 20 + sequencia % 10,
        }

    if servico == "inversao-termica":
        return {
            "estacao": identificador,
            "timestamp": timestamp,
            "umidade": 40 + sequencia % 40,
            "temperatura_c": 20 + sequencia % 10,
            "vento_km_h": 10 + sequencia % 10,
        }

    sensores = [
        ("nivel_corrego", "cm", 100),
        ("chuva", "mm", 10),
        ("velocidade_agua", "m/s", 2),
    ]
    tipo, unidade, valor = sensores[sequencia % 3]
    return {
        "sensor_id": f"{tipo}-{trabalhador}",
        "ponto_id": f"CARGA-alagamento-{trabalhador}-{sequencia // 3}",
        "tipo": tipo,
        "unidade": unidade,
        "valor": valor,
        "timestamp": timestamp,
    }


def executar(url_base, concorrencia_por_api, duracao, timeout):
    base = obter_base_local(url_base)
    preparar_duas_replicas()
    urls = {servico: base + rota for servico, rota in SERVICOS.items()}
    total_trabalhadores = concorrencia_por_api * len(SERVICOS)
    inicio = time.perf_counter()
    fim = inicio + duracao
    bloqueio = threading.Lock()
    latencias = {servico: [] for servico in SERVICOS}
    codigos = {servico: Counter() for servico in SERVICOS}
    pods = {servico: Counter() for servico in SERVICOS}
    erros = {servico: Counter() for servico in SERVICOS}
    concluidas_no_segundo = Counter()
    requisicoes = []

    def trabalhador(servico, numero):
        sequencia = 0
        timestamp_grupo = None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        url = urls[servico]

        while time.perf_counter() < fim:
            if servico == "alagamento" and sequencia % 3 == 0:
                timestamp_grupo = dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")
            dados = json.dumps(
                corpo_leitura(servico, numero, sequencia, timestamp_grupo)
            ).encode("utf-8")
            requisicao = urllib.request.Request(
                url,
                data=dados,
                method="POST",
                headers={"Content-Type": "application/json", "Connection": "close"},
            )
            comeco = time.perf_counter()
            inicio_requisicao = dt.datetime.now().astimezone().isoformat(timespec="milliseconds")
            codigo = 0
            pod = ""
            erro = ""

            try:
                with opener.open(requisicao, timeout=timeout) as resposta:
                    resposta.read()
                    codigo = resposta.status
                    pod = resposta.headers.get("X-Pod", "")
            except urllib.error.HTTPError as excecao:
                codigo = excecao.code
                pod = excecao.headers.get("X-Pod", "")
                erro = f"http_{excecao.code}"
                excecao.close()
            except (TimeoutError, socket.timeout):
                erro = "timeout"
            except urllib.error.URLError as excecao:
                erro = "timeout" if isinstance(excecao.reason, (TimeoutError, socket.timeout)) else "conexao"
            except OSError:
                erro = "conexao"

            latencia = (time.perf_counter() - comeco) * 1000
            with bloqueio:
                latencias[servico].append(latencia)
                codigos[servico][str(codigo)] += 1
                if pod:
                    pods[servico][pod] += 1
                if erro:
                    erros[servico][erro] += 1
                concluidas_no_segundo[servico] += 1
                requisicoes.append(
                    {
                        "inicio": inicio_requisicao,
                        "servico": servico,
                        "trabalhador": numero,
                        "sequencia": sequencia,
                        "url": url,
                        "status": codigo,
                        "pod": pod,
                        "erro": erro,
                        "latencia_ms": f"{latencia:.6f}",
                    }
                )
            sequencia += 1

    print("Carga simultânea nas três APIs")
    print(f"Ingress: {base}")
    print(
        f"{concorrencia_por_api} trabalhadores por API | "
        f"{total_trabalhadores} trabalhadores no total | {duracao} segundos"
    )
    for servico, url in urls.items():
        print(f"- {servico}: POST {url}")

    with concurrent.futures.ThreadPoolExecutor(max_workers=total_trabalhadores) as executor:
        tarefas = [
            executor.submit(trabalhador, servico, numero)
            for servico in SERVICOS
            for numero in range(concorrencia_por_api)
        ]
        segundo = 0
        while time.perf_counter() < fim:
            time.sleep(min(1.0, max(0.0, fim - time.perf_counter())))
            segundo += 1
            with bloqueio:
                por_servico = {servico: concluidas_no_segundo[servico] for servico in SERVICOS}
                concluidas_no_segundo.clear()
                todas_latencias = [valor for lista in latencias.values() for valor in lista]
                p95_atual = percentil(todas_latencias, 95)
                total_erros = sum(sum(contador.values()) for contador in erros.values())
            print(
                f"{agora()} | segundo={segundo:02d} | total/s={sum(por_servico.values())} | "
                f"manancial={por_servico['manancial']} | alagamento={por_servico['alagamento']} | "
                f"inversão={por_servico['inversao-termica']} | p95={p95_atual:.2f} ms | "
                f"erros={total_erros}"
            )
        for tarefa in tarefas:
            tarefa.result()

    tempo_total = time.perf_counter() - inicio
    todas_latencias = [valor for lista in latencias.values() for valor in lista]
    total_requisicoes = len(todas_latencias)
    total_erros = sum(sum(contador.values()) for contador in erros.values())
    total_sucessos = total_requisicoes - total_erros

    arquivo_csv = Path(__file__).resolve().parent / "requisicoes.csv"
    campos_csv = [
        "inicio",
        "servico",
        "trabalhador",
        "sequencia",
        "url",
        "status",
        "pod",
        "erro",
        "latencia_ms",
    ]
    requisicoes_ordenadas = sorted(
        requisicoes, key=lambda item: float(item["latencia_ms"]), reverse=True
    )
    mais_lenta = requisicoes_ordenadas[0]
    linhas_csv = []
    for requisicao in requisicoes_ordenadas:
        linha = dict(requisicao)
        linha["latencia_ms"] = linha["latencia_ms"].replace(".", ",")
        linhas_csv.append(linha)

    with arquivo_csv.open("w", newline="", encoding="utf-8-sig") as arquivo:
        escritor = csv.DictWriter(arquivo, fieldnames=campos_csv, delimiter=";")
        escritor.writeheader()
        escritor.writerows(linhas_csv)

    print("\nRESULTADO GERAL")
    print(
        f"{total_sucessos / tempo_total:.2f} req/s | "
        f"p95 {percentil(todas_latencias, 95):.2f} ms | "
        f"requisições {total_requisicoes} | erros {total_erros}"
    )

    for servico in SERVICOS:
        quantidade = len(latencias[servico])
        erros_servico = sum(erros[servico].values())
        sucessos_servico = quantidade - erros_servico
        print(f"\n{servico.upper()}")
        print(
            f"{sucessos_servico / tempo_total:.2f} req/s | "
            f"p95 {percentil(latencias[servico], 95):.2f} ms | "
            f"requisições {quantidade} | erros {erros_servico}"
        )
        print(f"Códigos HTTP: {dict(codigos[servico])}")
        print('Resumo "por pod":')
        if pods[servico]:
            for pod, quantidade_pod in pods[servico].most_common():
                print(f"- {pod}: {quantidade_pod}")
        else:
            print("- Cabeçalho X-Pod não recebido")
        if erros[servico]:
            print(f"Erros: {dict(erros[servico])}")

    print("\nREQUISIÇÃO COM MAIOR LATÊNCIA")
    print(f"Serviço: {mais_lenta['servico']}")
    print(f"Latência: {float(mais_lenta['latencia_ms']):.2f} ms")
    print(f"Status HTTP: {mais_lenta['status']}")
    print(f"Pod: {mais_lenta['pod'] or 'não informado'}")
    print(f"Início: {mais_lenta['inicio']}")
    print(f"Arquivo CSV: {arquivo_csv}")


def main():
    parser = argparse.ArgumentParser(
        description="Executa carga simultânea nas três APIs pelo Ingress local."
    )
    parser.add_argument("url", help="URL base do Ingress, por exemplo http://127.0.0.1:8088")
    parser.add_argument("-c", "--concorrencia", type=int, default=8, help="trabalhadores por API")
    parser.add_argument("-d", "--duracao", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=5.0)
    argumentos = parser.parse_args()

    if not 1 <= argumentos.concorrencia <= 85:
        parser.error("A concorrência por API deve estar entre 1 e 85.")
    if argumentos.duracao < 1:
        parser.error("A duração deve ser positiva.")
    if argumentos.timeout <= 0:
        parser.error("O timeout deve ser positivo.")

    executar(argumentos.url, argumentos.concorrencia, argumentos.duracao, argumentos.timeout)


if __name__ == "__main__":
    main()
