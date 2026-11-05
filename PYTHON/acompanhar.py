"""Cliente simples para observar o SSE no terminal."""
import argparse
import json
from urllib.request import Request, urlopen
from configuracao import url_api
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("servico", choices=["manancial", "alagamento", "inversao-termica"])
args=parser.parse_args()
porta={"manancial":8081,"alagamento":8082,"inversao-termica":8083}[args.servico]
url=url_api(args.servico.upper().replace("-","_"),porta).replace("/leituras","/tempo-real")
print(f"SSE conectado a {url}; Ctrl+C encerra.",flush=True)
try:
    with urlopen(Request(url,headers={"Accept":"text/event-stream"}),timeout=20) as resposta:
        for linha in resposta:
            texto=linha.decode("utf-8").strip()
            if texto.startswith("data: "):
                print("Leitura ao vivo:",json.dumps(json.loads(texto[6:]),ensure_ascii=False),flush=True)
except KeyboardInterrupt:
    print("Conexao encerrada.")
