from pathlib import Path
from configuracao import banco_docker
import sqlite3
from contextlib import closing

# Mesmo arquivo compartilhado pelos tres containers e aberto no DB Browser.
BANCO = banco_docker()
TABELAS = ("leituras_manancial", "leituras_alagamento", "leituras_inversaotermica")


def limpar_banco(caminho=BANCO):
    """Apaga todas as leituras, preservando tabelas, colunas e sequencia dos IDs."""
    caminho = Path(caminho).resolve()
    if not caminho.is_file():
        raise FileNotFoundError(f"Banco nao encontrado: {caminho}")
    # mode=rw impede a criacao acidental de um banco vazio em outro caminho.
    with closing(sqlite3.connect(caminho.as_uri() + "?mode=rw", uri=True, timeout=10)) as conexao:
        with conexao:
            conexao.execute("BEGIN IMMEDIATE")
            removidos = {}
            existentes = {linha[0] for linha in conexao.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            # Remove as notificacoes pendentes junto das leituras para nao envia-las apos a limpeza.
            for tabela in ("alertas_manancial", "alertas_alagamento", "alertas_inversaotermica"):
                if tabela in existentes:
                    removidos[tabela] = conexao.execute(f"DELETE FROM {tabela}").rowcount
            for tabela in TABELAS:
                # Nomes fixos definidos acima, sem entrada externa na consulta.
                cursor = conexao.execute(f"DELETE FROM {tabela}")
                removidos[tabela] = cursor.rowcount
    return removidos


if __name__ == "__main__":
    print(f"Limpando todas as leituras de: {BANCO}")
    print("Pare os geradores antes: novos envios podem preencher o banco novamente.")
    try:
        removidos = limpar_banco()
    except (sqlite3.Error, OSError) as erro:
        print(f"Nao foi possivel limpar o banco: {erro}")
        raise SystemExit(1)
    for tabela, quantidade in removidos.items():
        print(f"{tabela}: {quantidade} registro(s) apagado(s).")
    print(f"Concluido. Total apagado: {sum(removidos.values())}.")
