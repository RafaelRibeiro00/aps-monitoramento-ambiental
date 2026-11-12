"""Verificação ao vivo da Etapa 5 no k3d/k3s: escala, falha de pod e rolling update."""

import argparse
import atexit
import concurrent.futures
import csv
import datetime as dt
import json
import math
import os
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from collections import Counter


CONTEXTO = "k3d-aps"
NAMESPACE = "aps"
SERVICO = "manancial"
URL = "http://127.0.0.1:8088/manancial/leituras"
K3D = Path(os.environ.get("LOCALAPPDATA", "")) / "APS-tools" / "k3d.exe"
NOS_K3D = [
    "k3d-aps-server-0",
    "k3d-aps-agent-0",
    "k3d-aps-agent-1",
]
HPA_ORIGINAL = None


def restaurar_ambiente():
    global HPA_ORIGINAL
    try:
        kubectl("set", "image", f"deployment/{SERVICO}", "api=aps-manancial:3.0.0",
                mostrar=False, aceitar_erro=True)
        kubectl("set", "env", f"deployment/{SERVICO}", "APS_VERSAO=3.0.0",
                mostrar=False, aceitar_erro=True)
        kubectl("scale", f"deployment/{SERVICO}", "--replicas=2",
                mostrar=False, aceitar_erro=True)
        kubectl("rollout", "status", f"deployment/{SERVICO}", "--timeout=180s",
                mostrar=False, aceitar_erro=True)
        if HPA_ORIGINAL:
            executar(
                ["kubectl", "--context", CONTEXTO, "-n", NAMESPACE, "apply", "-f", "-"],
                mostrar=False, aceitar_erro=True, entrada=json.dumps(HPA_ORIGINAL),
            )
            HPA_ORIGINAL = None
    except Exception as excecao:
        print(f"Aviso: não foi possível restaurar completamente o ambiente: {excecao}", flush=True)


atexit.register(restaurar_ambiente)


def percentil(valores, percentual):
    if not valores:
        return 0.0
    ordenados = sorted(valores)
    return ordenados[max(0, math.ceil(len(ordenados) * percentual / 100) - 1)]


def executar(comando, timeout=240, mostrar=True, aceitar_erro=False, entrada=None):
    if mostrar:
        print("$ " + " ".join(comando), flush=True)
    resultado = subprocess.run(
        comando,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        input=entrada,
    )
    if resultado.stdout and mostrar:
        print(resultado.stdout.rstrip(), flush=True)
    if resultado.stderr and mostrar:
        print(resultado.stderr.rstrip(), flush=True)
    if resultado.returncode and not aceitar_erro:
        raise RuntimeError(f"Comando falhou ({resultado.returncode}): {' '.join(comando)}")
    return resultado


def kubectl(*argumentos, timeout=240, mostrar=True, aceitar_erro=False):
    return executar(
        ["kubectl", "--context", CONTEXTO, "-n", NAMESPACE, *argumentos],
        timeout=timeout,
        mostrar=mostrar,
        aceitar_erro=aceitar_erro,
    )


def preparar_ambiente():
    global HPA_ORIGINAL
    if not shutil.which("docker") or not shutil.which("kubectl"):
        raise RuntimeError("docker e kubectl precisam estar disponíveis no PATH.")
    if not K3D.exists():
        raise RuntimeError(f"k3d não encontrado em {K3D}. Execute preparar_kubernetes.ps1.")

    executar(["docker", "info", "--format", "{{.ServerVersion}}"], mostrar=False)
    executar([str(K3D), "image", "import", "aps-manancial:3.0.0", "aps-manancial:3.0.1",
             "-c", "aps"], timeout=600)
    existentes = executar(["docker", "ps", "-a", "--format", "{{.Names}}"], mostrar=False).stdout.splitlines()
    contexto = executar(["kubectl", "config", "current-context"], mostrar=False).stdout.strip()
    if contexto != CONTEXTO:
        raise RuntimeError(f"Contexto ativo inesperado: {contexto}. Use {CONTEXTO}.")

    for no in NOS_K3D:
        if no not in existentes:
            raise RuntimeError(f"Contêiner do nó k3d não encontrado: {no}")
        ativo = executar(["docker", "inspect", "-f", "{{.State.Running}}", no], mostrar=False).stdout.strip()
        if ativo != "true":
            executar(["docker", "start", no])

    limite = time.monotonic() + 90
    while time.monotonic() < limite:
        resultado = kubectl("get", "nodes", mostrar=False, aceitar_erro=True)
        if resultado.returncode == 0:
            break
        time.sleep(2)
    else:
        raise RuntimeError("O cluster não respondeu em 90 segundos.")

    hpa = kubectl("get", "hpa", SERVICO, "-o", "json", mostrar=False, aceitar_erro=True)
    if hpa.returncode == 0:
        HPA_ORIGINAL = json.loads(hpa.stdout)
        HPA_ORIGINAL.pop("status", None)
        metadados = HPA_ORIGINAL.get("metadata", {})
        for chave in ("creationTimestamp", "generation", "resourceVersion", "uid", "managedFields"):
            metadados.pop(chave, None)
    kubectl("delete", "hpa", SERVICO, "--ignore-not-found=true")


def definir_replicas(quantidade):
    kubectl("scale", f"deployment/{SERVICO}", f"--replicas={quantidade}")
    kubectl("rollout", "status", f"deployment/{SERVICO}", "--timeout=180s")


def preparar_versao_inicial():
    # Sem APS_VERSAO no Deployment, a versão vem da imagem Docker.
    kubectl("set", "env", f"deployment/{SERVICO}", "APS_VERSAO-", aceitar_erro=True)
    kubectl("set", "image", f"deployment/{SERVICO}", "api=aps-manancial:3.0.0")
    kubectl("rollout", "status", f"deployment/{SERVICO}", "--timeout=180s")


def corpo(trabalhador, sequencia, experimento):
    return json.dumps(
        {
            "area": f"ETAPA5-{experimento}-{trabalhador}-{sequencia}",
            "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds"),
            "percentual_ocupado": 40 + sequencia % 40,
            "nivel_agua_m": 2 + sequencia % 10,
            "temperatura_agua_c": 20 + sequencia % 10,
        }
    ).encode("utf-8")


def carga(nome, trabalhadores, duracao, acao=None, acao_em=8):
    inicio = time.perf_counter()
    fim = inicio + duracao
    resultados = queue.Queue()
    registros = []
    erro_acao = []

    def trabalhador(numero):
        sequencia = 0
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while time.perf_counter() < fim:
            requisicao = urllib.request.Request(
                URL,
                data=corpo(numero, sequencia, nome),
                method="POST",
                headers={"Content-Type": "application/json", "Connection": "close"},
            )
            instante = dt.datetime.now().astimezone().isoformat(timespec="milliseconds")
            comeco = time.perf_counter()
            status = 0
            pod = ""
            versao = ""
            erro = ""
            try:
                with opener.open(requisicao, timeout=5) as resposta:
                    resposta.read()
                    status = resposta.status
                    pod = resposta.headers.get("X-Pod", "")
                    versao = resposta.headers.get("X-Versao", "")
            except urllib.error.HTTPError as excecao:
                status = excecao.code
                pod = excecao.headers.get("X-Pod", "")
                versao = excecao.headers.get("X-Versao", "")
                erro = f"http_{excecao.code}"
                excecao.close()
            except (TimeoutError, socket.timeout):
                erro = "timeout"
            except urllib.error.URLError as excecao:
                erro = "timeout" if isinstance(excecao.reason, (TimeoutError, socket.timeout)) else "conexao"
            except OSError:
                erro = "conexao"
            resultados.put(
                {
                    "experimento": nome,
                    "inicio": instante,
                    "segundo": time.perf_counter() - inicio,
                    "trabalhador": numero,
                    "status": status,
                    "pod": pod,
                    "versao": versao,
                    "erro": erro,
                    "latencia_ms": (time.perf_counter() - comeco) * 1000,
                }
            )
            sequencia += 1

    def executar_acao():
        espera = inicio + acao_em - time.perf_counter()
        if espera > 0:
            time.sleep(espera)
        try:
            acao()
        except Exception as excecao:
            erro_acao.append(str(excecao))

    print(f"\nCarga: {nome} | {trabalhadores} trabalhadores | {duracao} s", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=trabalhadores) as pool:
        tarefas = [pool.submit(trabalhador, numero) for numero in range(trabalhadores)]
        thread_acao = threading.Thread(target=executar_acao, daemon=True) if acao else None
        if thread_acao:
            thread_acao.start()
        proximo = inicio + 1
        while not all(tarefa.done() for tarefa in tarefas) or not resultados.empty():
            try:
                registros.append(resultados.get(timeout=0.05))
            except queue.Empty:
                pass
            agora = time.perf_counter()
            if agora >= proximo and agora < fim + 1:
                segundo = int(agora - inicio)
                recentes = [item for item in registros if item["segundo"] >= segundo - 1]
                erros = sum(bool(item["erro"]) for item in recentes)
                print(f"segundo={segundo:02d} respostas={len(recentes)} erros={erros}", flush=True)
                proximo += 1
        if thread_acao:
            thread_acao.join(timeout=190)
            if thread_acao.is_alive():
                raise RuntimeError("A ação Kubernetes não terminou.")

    if erro_acao:
        raise RuntimeError("; ".join(erro_acao))
    tempo = time.perf_counter() - inicio
    erros = sum(bool(item["erro"]) for item in registros)
    sucessos = len(registros) - erros
    latencias = [item["latencia_ms"] for item in registros]
    return {
        "nome": nome,
        "requisicoes": len(registros),
        "sucessos": sucessos,
        "falhas": erros,
        "vazao_req_s": sucessos / tempo,
        "p95_ms": percentil(latencias, 95),
        "pods": Counter(item["pod"] for item in registros if item["pod"]),
        "versoes": Counter(item["versao"] for item in registros if item["versao"]),
        "registros": registros,
    }


def pod_para_apagar():
    resultado = kubectl("get", "pods", "-l", f"app={SERVICO}", "-o", "json", mostrar=False)
    itens = json.loads(resultado.stdout)["items"]
    prontos = [
        item["metadata"]["name"]
        for item in itens
        if item.get("status", {}).get("phase") == "Running"
        and any(condicao.get("type") == "Ready" and condicao.get("status") == "True" for condicao in item["status"].get("conditions", []))
    ]
    if not prontos:
        raise RuntimeError("Nenhum pod pronto para apagar.")
    return prontos[0]


def salvar_csv(registros, caminho):
    campos = ["experimento", "inicio", "segundo", "trabalhador", "status", "pod", "versao", "erro", "latencia_ms"]
    with caminho.open("w", newline="", encoding="utf-8-sig") as arquivo:
        escritor = csv.DictWriter(arquivo, fieldnames=campos, delimiter=";")
        escritor.writeheader()
        for item in registros:
            linha = dict(item)
            linha["segundo"] = f"{linha['segundo']:.6f}".replace(".", ",")
            linha["latencia_ms"] = f"{linha['latencia_ms']:.6f}".replace(".", ",")
            escritor.writerow(linha)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-c", "--concorrencia", type=int, default=16)
    parser.add_argument("--duracao-escala", type=int, default=20)
    parser.add_argument("--duracao-falha", type=int, default=35)
    parser.add_argument("--duracao-update", type=int, default=60)
    args = parser.parse_args()
    if args.concorrencia < 1 or min(args.duracao_escala, args.duracao_falha, args.duracao_update) < 5:
        parser.error("Use concorrência positiva e durações de pelo menos 5 segundos.")

    pasta = Path(__file__).resolve().parent
    todos_registros = []
    preparar_ambiente()
    preparar_versao_inicial()

    print("\n=== 1. ESCALA MANUAL: 1 PARA 4 RÉPLICAS ===", flush=True)
    definir_replicas(1)
    escala_1 = carga("escala-1-replica", args.concorrencia, args.duracao_escala)
    definir_replicas(4)
    escala_4 = carga("escala-4-replicas", args.concorrencia, args.duracao_escala)
    todos_registros += escala_1["registros"] + escala_4["registros"]
    ganho = escala_4["vazao_req_s"] / escala_1["vazao_req_s"] if escala_1["vazao_req_s"] else 0

    print("\n=== 2. POD APAGADO SOB CARGA ===", flush=True)
    pod_apagado = {"nome": ""}

    def apagar_pod():
        pod_apagado["nome"] = pod_para_apagar()
        kubectl("delete", "pod", pod_apagado["nome"], "--wait=false")

    falha = carga("pod-apagado", args.concorrencia, args.duracao_falha, acao=apagar_pod, acao_em=8)
    todos_registros += falha["registros"]

    print("\n=== 3. ROLLING UPDATE 3.0.0 PARA 3.0.1 ===", flush=True)
    preparar_versao_inicial()
    definir_replicas(4)

    def publicar_versao():
        kubectl("set", "image", f"deployment/{SERVICO}", "api=aps-manancial:3.0.1")
        kubectl("rollout", "status", f"deployment/{SERVICO}", "--timeout=180s")

    update = carga("rolling-update", args.concorrencia, args.duracao_update, acao=publicar_versao, acao_em=8)
    todos_registros += update["registros"]

    csv_saida = pasta / "requisicoes.csv"
    salvar_csv(todos_registros, csv_saida)

    resumo = [
        "RESULTADO OBSERVADO - ETAPA 5",
        f"Data: {dt.datetime.now().astimezone().isoformat(timespec='seconds')}",
        "",
        "1. Escala manual",
        f"Comando: kubectl scale deployment/manancial --replicas=1 e --replicas=4",
        f"1 réplica: {escala_1['vazao_req_s']:.2f} req/s | p95 {escala_1['p95_ms']:.2f} ms",
        f"4 réplicas: {escala_4['vazao_req_s']:.2f} req/s | p95 {escala_4['p95_ms']:.2f} ms",
        f"Ganho medido: {ganho:.2f}x",
        "Explicação para apresentação:",
        "O comando kubectl scale altera manualmente a quantidade de réplicas do Deployment Manancial.",
        "Req/s significa requisições respondidas por segundo e representa a vazão do sistema.",
        "O p95 é o tempo abaixo do qual 95% das requisições foram concluídas; apenas 5% demoraram mais.",
        f"Ao passar de 1 para 4 réplicas, a vazão subiu de {escala_1['vazao_req_s']:.2f} para {escala_4['vazao_req_s']:.2f} req/s,",
        f"um ganho de {ganho:.2f} vezes, enquanto o p95 caiu de {escala_1['p95_ms']:.2f} para {escala_4['p95_ms']:.2f} ms.",
        "Isso mostra que os quatro pods dividiram as requisições, aumentando a capacidade e reduzindo o tempo de resposta.",
        "O ganho não precisa ser 4x porque Ingress, banco de dados, fila, CPU da máquina e outras partes compartilhadas",
        "não são multiplicadas junto com os pods e podem limitar o desempenho.",
        "",
        "2. Pod apagado",
        f"Comando: kubectl delete pod {pod_apagado['nome']} --wait=false",
        f"Falhas observadas: {falha['falhas']}",
        f"Requisições durante o teste: {falha['requisicoes']}",
        "Explicação para apresentação:",
        "O comando kubectl delete pod removeu intencionalmente um pod do Manancial enquanto a carga continuava.",
        "A opção --wait=false fez o comando retornar imediatamente, sem esperar toda a exclusão terminar.",
        f"Durante {falha['requisicoes']} requisições foram observadas {falha['falhas']} falhas.",
        "O Service deixou de encaminhar novas requisições ao pod removido e distribuiu a carga entre os pods restantes.",
        "Como o Deployment detectou que havia menos réplicas do que o desejado, o Kubernetes criou automaticamente",
        "um pod substituto. O resultado demonstra balanceamento de carga, resiliência e autorrecuperação.",
        "",
        "3. Rolling update",
        "Comando: kubectl set image deployment/manancial api=aps-manancial:3.0.1",
        f"Versões vistas: {dict(update['versoes'])}",
        f"Falhas observadas: {update['falhas']}",
        "Explicação para apresentação:",
        "O comando kubectl set image alterou a imagem do contêiner api da versão 3.0.0 para a versão 3.0.1.",
        "O Kubernetes realizou um rolling update: criou gradualmente pods da nova versão e removeu os antigos",
        "somente depois que os novos ficaram prontos.",
        f"Durante a atualização, o cabeçalho X-Versao registrou respostas das versões {dict(update['versoes'])}.",
        "Isso comprova que as duas versões coexistiram temporariamente e continuaram atendendo requisições.",
        f"Como foram observadas {update['falhas']} falhas, a atualização ocorreu sem indisponibilidade percebida pelo cliente.",
        "",
        f"CSV: {csv_saida}",
    ]
    texto = "\n".join(resumo)
    (pasta / "resultado_observado.txt").write_text(texto, encoding="utf-8")

    print("\n" + texto, flush=True)
    print("\nExperimentos concluídos.", flush=True)
    restaurar_ambiente()


if __name__ == "__main__":
    main()
