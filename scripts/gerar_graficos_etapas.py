# /// script
# requires-python = ">=3.11"
# dependencies = ["matplotlib>=3.10"]
# ///

"""Gera painéis de resultados das Etapas 4 e 5 a partir dos CSVs."""

from __future__ import annotations

import csv
import math
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


DOWNLOADS = Path.home() / "Downloads"
CSV_ETAPA4 = DOWNLOADS / "Etapa4" / "requisicoes.csv"
CSV_ETAPA5 = DOWNLOADS / "Etapa5" / "requisicoes.csv"
PNG_ETAPA4 = DOWNLOADS / "Etapa4" / "grafico_etapa4.png"
PNG_ETAPA5 = DOWNLOADS / "Etapa5" / "grafico_etapa5.png"

CORES = {
    "manancial": "#2563eb",
    "alagamento": "#0891b2",
    "inversao-termica": "#7c3aed",
    "total": "#111827",
    "erro": "#dc2626",
    "3.0.0": "#2563eb",
    "3.0.1": "#16a34a",
}


def numero(texto: str | None) -> float:
    return float((texto or "0").replace(",", "."))


def percentil(valores: list[float], percentual: float) -> float:
    if not valores:
        return 0.0
    ordenados = sorted(valores)
    indice = max(0, math.ceil(len(ordenados) * percentual / 100) - 1)
    return ordenados[indice]


def sucesso(registro: dict) -> bool:
    try:
        status = int(registro.get("status") or 0)
    except ValueError:
        status = 0
    return 200 <= status < 400 and not (registro.get("erro") or "").strip()


def ler_etapa4() -> list[dict]:
    with CSV_ETAPA4.open("r", encoding="utf-8-sig", newline="") as arquivo:
        linhas = list(csv.DictReader(arquivo, delimiter=";"))
    if not linhas:
        raise ValueError(f"CSV sem registros: {CSV_ETAPA4}")
    for linha in linhas:
        linha["momento"] = datetime.fromisoformat(linha["inicio"])
        linha["latencia"] = numero(linha["latencia_ms"])
    return linhas


def ler_etapa5() -> list[dict]:
    with CSV_ETAPA5.open("r", encoding="utf-8-sig", newline="") as arquivo:
        linhas = list(csv.DictReader(arquivo, delimiter=";"))
    if not linhas:
        raise ValueError(f"CSV sem registros: {CSV_ETAPA5}")
    for linha in linhas:
        linha["segundo_num"] = numero(linha["segundo"])
        linha["latencia"] = numero(linha["latencia_ms"])
    return linhas


def estilo_eixos(eixo, grade=True):
    eixo.spines["top"].set_visible(False)
    eixo.spines["right"].set_visible(False)
    if grade:
        eixo.grid(axis="y", color="#e5e7eb", linewidth=0.8)
        eixo.set_axisbelow(True)


def rotulo_barras(eixo, barras, formato="{:.1f}"):
    for barra in barras:
        valor = barra.get_height()
        eixo.annotate(
            formato.format(valor),
            (barra.get_x() + barra.get_width() / 2, valor),
            xytext=(0, 4),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=9,
        )


def gerar_etapa4(registros: list[dict]):
    servicos = ["manancial", "alagamento", "inversao-termica"]
    nomes = ["Manancial", "Alagamento", "Inversão térmica"]
    inicio = min(item["momento"] for item in registros)
    fim = max(item["momento"] for item in registros)
    duracao = max(1.0, (fim - inicio).total_seconds())

    por_segundo = defaultdict(Counter)
    total_segundo = Counter()
    for item in registros:
        segundo = int((item["momento"] - inicio).total_seconds()) + 1
        if sucesso(item):
            por_segundo[item["servico"]][segundo] += 1
            total_segundo[segundo] += 1

    segundos = list(range(1, max(total_segundo, default=1) + 1))
    metricas = {}
    for servico in servicos:
        itens = [x for x in registros if x["servico"] == servico]
        latencias = [x["latencia"] for x in itens]
        erros = sum(not sucesso(x) for x in itens)
        metricas[servico] = {
            "p50": percentil(latencias, 50),
            "p95": percentil(latencias, 95),
            "p99": percentil(latencias, 99),
            "erros": 100 * erros / len(itens) if itens else 0,
        }

    fig, eixos = plt.subplots(2, 2, figsize=(16, 10), constrained_layout=True)
    fig.suptitle(
        f"Etapa 4 - Carga simultânea nas três APIs\n"
        f"{len(registros):,} requisições registradas em {duracao:.1f} s".replace(",", "."),
        fontsize=17,
        fontweight="bold",
        color="#111827",
    )

    ax = eixos[0, 0]
    for servico, nome in zip(servicos, nomes):
        ax.plot(
            segundos,
            [por_segundo[servico][s] for s in segundos],
            marker="o",
            markersize=3,
            linewidth=2,
            label=nome,
            color=CORES[servico],
        )
    ax.plot(
        segundos,
        [total_segundo[s] for s in segundos],
        linewidth=2.4,
        linestyle="--",
        label="Total",
        color=CORES["total"],
    )
    ax.set_title("Vazão ao longo do teste")
    ax.set_xlabel("Segundo do teste")
    ax.set_ylabel("Respostas por segundo")
    ax.legend(ncol=2, frameon=False)
    estilo_eixos(ax)

    ax = eixos[0, 1]
    posicoes = list(range(len(servicos)))
    largura = 0.24
    for deslocamento, chave, cor in [
        (-largura, "p50", "#60a5fa"),
        (0, "p95", "#2563eb"),
        (largura, "p99", "#1e3a8a"),
    ]:
        barras = ax.bar(
            [p + deslocamento for p in posicoes],
            [metricas[s][chave] for s in servicos],
            width=largura,
            label=chave.upper(),
            color=cor,
        )
        rotulo_barras(ax, barras, "{:.0f}")
    ax.set_title("Latência por serviço")
    ax.set_xticks(posicoes, nomes)
    ax.set_ylabel("Milissegundos")
    ax.legend(frameon=False)
    estilo_eixos(ax)

    ax = eixos[1, 0]
    barras = ax.bar(
        nomes,
        [metricas[s]["erros"] for s in servicos],
        color=[CORES[s] if metricas[s]["erros"] == 0 else CORES["erro"] for s in servicos],
    )
    rotulo_barras(ax, barras, "{:.2f}%")
    ax.set_title("Taxa de erro por serviço")
    ax.set_ylabel("Erros (%)")
    ax.set_ylim(bottom=0)
    estilo_eixos(ax)

    ax = eixos[1, 1]
    pods = Counter(item.get("pod") or "Sem pod" for item in registros if sucesso(item))
    itens_pod = sorted(pods.items(), key=lambda item: item[1])
    nomes_pod = [nome for nome, _ in itens_pod]
    valores_pod = [valor for _, valor in itens_pod]
    barras = ax.barh(nomes_pod, valores_pod, color="#475569")
    for barra, valor in zip(barras, valores_pod):
        ax.text(valor, barra.get_y() + barra.get_height() / 2, f" {valor}", va="center", fontsize=9)
    ax.set_title("Distribuição das respostas por pod")
    ax.set_xlabel("Respostas")
    estilo_eixos(ax)

    fig.text(0.01, 0.005, "Fonte: requisicoes.csv gerado pelo loadgen da Etapa 4.", fontsize=9, color="#475569")
    fig.savefig(PNG_ETAPA4, dpi=180, facecolor="white", bbox_inches="tight", pad_inches=0.18)
    plt.close(fig)


def resumo_experimento(registros: list[dict], nome: str) -> dict:
    itens = [x for x in registros if x["experimento"] == nome]
    duracao = max((x["segundo_num"] for x in itens), default=1.0)
    latencias = [x["latencia"] for x in itens]
    erros = sum(not sucesso(x) for x in itens)
    return {
        "itens": itens,
        "req_s": sum(sucesso(x) for x in itens) / max(duracao, 1.0),
        "p50": percentil(latencias, 50),
        "p95": percentil(latencias, 95),
        "p99": percentil(latencias, 99),
        "erros": erros,
    }


def serie_experimento(itens: list[dict]):
    respostas = Counter()
    erros = Counter()
    latencias = defaultdict(list)
    versoes = defaultdict(Counter)
    for item in itens:
        segundo = max(1, math.ceil(item["segundo_num"]))
        if sucesso(item):
            respostas[segundo] += 1
        else:
            erros[segundo] += 1
        latencias[segundo].append(item["latencia"])
        versao = item.get("versao") or "Não informada"
        versoes[segundo][versao] += 1
    limite = max([*respostas.keys(), *erros.keys()], default=1)
    segundos = list(range(1, limite + 1))
    p95 = [percentil(latencias[s], 95) for s in segundos]
    return segundos, respostas, erros, p95, versoes


def gerar_etapa5(registros: list[dict]):
    escala1 = resumo_experimento(registros, "escala-1-replica")
    escala4 = resumo_experimento(registros, "escala-4-replicas")
    falha = resumo_experimento(registros, "pod-apagado")
    update = resumo_experimento(registros, "rolling-update")

    fig, eixos = plt.subplots(2, 2, figsize=(16, 10), constrained_layout=True)
    fig.suptitle(
        f"Etapa 5 - Escalabilidade e resiliência\n{len(registros):,} requisições registradas".replace(",", "."),
        fontsize=17,
        fontweight="bold",
        color="#111827",
    )

    ax = eixos[0, 0]
    barras = ax.bar(
        [0, 1],
        [escala1["req_s"], escala4["req_s"]],
        color=["#93c5fd", "#2563eb"],
    )
    rotulo_barras(ax, barras, "{:.1f}")
    ganho = escala4["req_s"] / escala1["req_s"] if escala1["req_s"] else 0
    ax.text(
        0.5,
        0.94,
        f"Ganho medido: {ganho:.2f}x",
        transform=ax.transAxes,
        ha="center",
        va="top",
        fontsize=11,
        fontweight="bold",
    )
    ax.set_title("Escala manual - vazão")
    ax.set_xticks([0, 1], ["1 réplica", "4 réplicas"])
    ax.set_ylabel("Respostas por segundo")
    estilo_eixos(ax)

    ax = eixos[0, 1]
    posicoes = [0, 1]
    largura = 0.24
    for deslocamento, chave, cor in [
        (-largura, "p50", "#60a5fa"),
        (0, "p95", "#2563eb"),
        (largura, "p99", "#1e3a8a"),
    ]:
        barras = ax.bar(
            [p + deslocamento for p in posicoes],
            [escala1[chave], escala4[chave]],
            width=largura,
            label=chave.upper(),
            color=cor,
        )
        rotulo_barras(ax, barras, "{:.0f}")
    ax.set_title("Escala manual - latência")
    ax.set_xticks(posicoes, ["1 réplica", "4 réplicas"])
    ax.set_ylabel("Milissegundos")
    ax.legend(frameon=False)
    estilo_eixos(ax)

    ax = eixos[1, 0]
    segundos, respostas, erros, p95, _ = serie_experimento(falha["itens"])
    ax.plot(segundos, [respostas[s] for s in segundos], color="#2563eb", linewidth=2, label="Respostas/s")
    ax.bar(segundos, [erros[s] for s in segundos], color=CORES["erro"], alpha=0.75, label="Erros/s")
    ax.axvline(8, color="#111827", linestyle="--", linewidth=1.5, label="Pod apagado")
    ax.set_title(f"Resiliência - pod apagado ({falha['erros']} falhas)")
    ax.set_xlabel("Tempo do teste (s)")
    ax.set_ylabel("Quantidade por segundo")
    ax.legend(frameon=False, ncol=3, fontsize=9)
    estilo_eixos(ax)

    ax = eixos[1, 1]
    segundos, respostas, erros, p95, versoes = serie_experimento(update["itens"])
    base = [0] * len(segundos)
    versoes_vistas = sorted({v for contagem in versoes.values() for v in contagem if v != "Não informada"})
    for versao in versoes_vistas:
        valores = [versoes[s][versao] for s in segundos]
        ax.bar(
            segundos,
            valores,
            bottom=base,
            width=0.9,
            label=f"Versão {versao}",
            color=CORES.get(versao, "#64748b"),
        )
        base = [a + b for a, b in zip(base, valores)]
    ax.axvline(8, color="#111827", linestyle="--", linewidth=1.5, label="Início da atualização")
    ax.set_title(f"Rolling update - versões atendendo ({update['erros']} falhas)")
    ax.set_xlabel("Tempo do teste (s)")
    ax.set_ylabel("Respostas por segundo")
    ax.legend(frameon=False, ncol=2, fontsize=9)
    estilo_eixos(ax)

    fig.savefig(PNG_ETAPA5, dpi=180, facecolor="white", bbox_inches="tight", pad_inches=0.18)
    plt.close(fig)


def main():
    for caminho in (CSV_ETAPA4, CSV_ETAPA5):
        if not caminho.exists():
            raise FileNotFoundError(f"Arquivo não encontrado: {caminho}")
    gerar_etapa4(ler_etapa4())
    gerar_etapa5(ler_etapa5())
    print(f"Gráfico da Etapa 4: {PNG_ETAPA4}")
    print(f"Gráfico da Etapa 5: {PNG_ETAPA5}")


if __name__ == "__main__":
    main()
