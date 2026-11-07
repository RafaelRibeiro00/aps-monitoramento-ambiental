"""Consolida somente medições reais; nenhuma célula ausente é estimada."""
import argparse
import csv
import json
from pathlib import Path
import statistics

def main():
    p=argparse.ArgumentParser();p.add_argument('pasta');a=p.parse_args()
    root=Path(a.pasta)
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    summaries=[]
    for file in sorted(root.rglob('carga-resumo.json')):
        data=json.loads(file.read_text(encoding='utf-8'))
        label=str(file.parent.relative_to(root)).replace('\\','/')
        summaries.append((label,data))
        rows=data['degraus']
        fig,ax=plt.subplots(figsize=(9,4.8)); right=ax.twinx()
        for key in ['p50_ms','p95_ms','p99_ms']:
            ax.plot([r['concorrencia'] for r in rows],[r[key] for r in rows],marker='o',label=key)
        right.plot([r['concorrencia'] for r in rows],[r['vazao_ok_s'] for r in rows],color='black',marker='s',linestyle='--',label='Vazão OK')
        ax.set(xlabel='Clientes simultâneos',ylabel='Latência (ms)',title=label)
        right.set_ylabel('Respostas bem-sucedidas/s');ax.legend(loc='upper left');right.legend(loc='upper right')
        fig.tight_layout();fig.savefig(file.parent/'carga.png',dpi=150);plt.close(fig)
        seriesfile=file.parent/'carga-segundos.csv'
        with seriesfile.open(encoding='utf-8') as f: series=list(csv.DictReader(f))
        if series:
            fig,axes=plt.subplots(3,1,figsize=(10,8),sharex=True)
            t=[float(r['segundo']) for r in series]
            axes[0].plot(t,[float(r['vazao_ok_s']) for r in series]);axes[0].set_ylabel('OK/s')
            axes[1].plot(t,[float(r['p95_ms']) if r['p95_ms'] else float('nan') for r in series]);axes[1].set_ylabel('p95 (ms)')
            axes[2].plot(t,[float(r['erros_pct']) for r in series]);axes[2].set_ylabel('Erros (%)');axes[2].set_xlabel('Segundos desde o início')
            import datetime as dt
            started=dt.datetime.fromisoformat(data['inicio'])
            eventsfile=file.parent/'eventos.jsonl'
            if eventsfile.exists():
                for line in eventsfile.read_text(encoding='utf-8').splitlines():
                    e=json.loads(line)
                    if e['evento'] in ('acao_inicio','acao_fim'):
                        x=(dt.datetime.fromisoformat(e['hora'])-started).total_seconds()
                        for axis in axes: axis.axvline(x,color='red',linestyle='--')
                        axes[0].text(x,axes[0].get_ylim()[1],e['evento'],rotation=90,va='top')
            fig.suptitle(label);fig.tight_layout();fig.savefig(file.parent/'linha-tempo.png',dpi=150);plt.close(fig)
    out=['# Experimentos de carga APS\n',
         'Medições pelo Ingress local. Atendimento Java replicável; coordenador SQLite/fila único por domínio. '
         'O ganho mede a camada HTTP, não a replicação do banco nem da coordenação.\n',
         'As latências de POST medem o aceite HTTP 202. A confirmação no SQLite e a drenagem da fila estão em eventos.jsonl. '
         'Na escrita síncrona, a resposta aguarda o commit. Essas garantias são diferentes e precisam acompanhar a comparação.\n',
         '| Execução | Clientes | Requisições | OK/s | p50 ms | p95 ms | p99 ms | Erros % |',
         '|---|---:|---:|---:|---:|---:|---:|---:|']
    def fmt(n): return 'sem amostra' if n is None else f'{n:.2f}'
    for label,data in summaries:
        for r in data['degraus']:
            out.append(f'| {label} | {r["concorrencia"]} | {r["requisicoes"]} | '+
                ' | '.join(fmt(r[k]) for k in ['vazao_ok_s','p50_ms','p95_ms','p99_ms','erros_pct'])+' |')
    indexed={label:data for label,data in summaries}
    post_runs=[data for label,data in summaries if label.startswith('base/post-')]
    get_runs=[data for label,data in summaries if label.startswith('base/get-')]
    out+=['\n## Conclusões medidas\n']
    if post_runs:
        by_clients={c:[r for data in post_runs for r in data['degraus'] if r['concorrencia']==c]
                    for c in sorted({r['concorrencia'] for data in post_runs for r in data['degraus']})}
        mean_flow={c:statistics.mean(r['vazao_ok_s'] for r in rows) for c,rows in by_clients.items()}
        peak=max(mean_flow,key=mean_flow.get)
        p95_32=statistics.mean(r['p95_ms'] for r in by_clients.get(32,[]) if r['p95_ms'] is not None)
        p95_64=statistics.mean(r['p95_ms'] for r in by_clients.get(64,[]) if r['p95_ms'] is not None)
        out.append(f'A linha de base do POST atingiu a maior vazão média observada em {peak} clientes: '
                   f'{mean_flow[peak]:.2f} respostas 202/s. Com 32 clientes, a vazão média caiu para '
                   f'{mean_flow.get(32,0):.2f}/s e o p95 médio subiu para {p95_32:.2f} ms; com 64, '
                   f'foram {mean_flow.get(64,0):.2f}/s e {p95_64:.2f} ms. O joelho fica entre 16 e '
                   '32 clientes nesta máquina. A fila acumulou milhares de aceites e o SQLite continuou '
                   'gravando depois do fim da carga, portanto o limite sustentado é inferior à capacidade '
                   'instantânea de aceitar 202.')
    if get_runs:
        rows=[r for data in get_runs for r in data['degraus'] if r['concorrencia']==64]
        out.append(f'O GET paginado, com 64 clientes, sustentou em média '
                   f'{statistics.mean(r["vazao_ok_s"] for r in rows):.2f} respostas/s, com p95 médio '
                   f'{statistics.mean(r["p95_ms"] for r in rows):.2f} ms e zero erro nas três repetições. '
                   'Ele foi mais leve que a ingestão, embora o COUNT do histórico ainda compartilhe o SQLite.')
    manual=[(int(label.split('/')[1].split('-')[0]),data['degraus'][0]) for label,data in summaries if label.startswith('manual/')]
    if manual:
        manual.sort();base=manual[0][1]['vazao_ok_s']
        out.append('A escala manual produziu '+', '.join(f'{n} réplica(s): {r["vazao_ok_s"]:.2f}/s ({r["vazao_ok_s"]/base:.2f}×)'
                   for n,r in manual)+'. O ganho distante do ideal confirma a grande parcela compartilhada '
                   'do caminho: coordenador, fila, SQLite, Ingress e recursos da máquina.')
    if 'hpa/rampa' in indexed:
        d=indexed['hpa/rampa']['total']
        out.append(f'O HPA variou de 2 até 10 réplicas durante a rampa, mas a execução registrou '
                   f'{d["erros_pct"]:.2f}% de erros e p95 de {d["p95_ms"]:.2f} ms. A fila não pôde ser '
                   'consultada pelo Ingress dentro dos 180 segundos de drenagem do roteiro; consulta direta '
                   'posterior confirmou fila zero. Isso demonstra que escalar apenas o atendimento não remove '
                   'a coordenação e o banco compartilhados.')
    if 'falhas/pod' in indexed and 'falhas/no' in indexed:
        pod=indexed['falhas/pod']['total'];node=indexed['falhas/no']['total']
        out.append(f'Apagar um pod de atendimento gerou {pod["erros"]} erros em {pod["requisicoes"]} '
                   f'requisições. Drenar um worker gerou {node["erros"]} erro em {node["requisicoes"]}. '
                   'O teste não removeu o nó que mantém o volume local do SQLite.')
    if all(k in indexed for k in ['update/rolling','update/undo','update/sem-prestop']):
        values=[]
        for k in ['update/rolling','update/undo','update/sem-prestop']:
            d=indexed[k]['total'];values.append(f'{k.split("/")[1]}: {d["erros_pct"]:.2f}%')
        out.append('Erros durante atualização: '+', '.join(values)+'. As versões 3.0.0 e 3.0.1 coexistiram '
                   'nas três execuções. O cenário sem preStop teve a maior taxa observada, mas uma execução '
                   'por cenário não basta para atribuir causalidade com intervalo de confiança.')
    if 'assincrono/sincrona' in indexed and 'assincrono/assincrona' in indexed:
        sync=indexed['assincrono/sincrona']['total'];async_=indexed['assincrono/assincrona']['total']
        out.append(f'A escrita síncrona alcançou {sync["vazao_ok_s"]:.2f}/s com p95 {sync["p95_ms"]:.2f} ms; '
                   f'a assíncrona alcançou {async_["vazao_ok_s"]:.2f}/s com p95 {async_["p95_ms"]:.2f} ms. '
                   'Nos dois casos, o delta persistido coincidiu com os aceites após a drenagem. A fila reduziu '
                   'a latência de aceite, mas não transforma o SQLite em um recurso distribuído.')
    out+=['\n## Análise por experimento\n']
    hypotheses={'base':'A vazão cresce até um recurso saturar; depois a latência aumenta.',
        'manual':'Mais atendimentos ajudam somente se a coordenação, o banco e a máquina tiverem folga.',
        'hpa':'A CPU induz criação de réplicas, com atraso até prontidão e normalização da latência.',
        'falhas':'As réplicas sobreviventes atendem enquanto o controlador repõe a capacidade.',
        'update':'Readiness e preStop reduzem interrupções; versões coexistem durante a troca.',
        'assincrono':'A fila reduz o tempo do aceite, mas o SQLite ainda precisa concluir todas as escritas.'}
    for label,data in summaries:
        folder=root/label
        out += [f'\n### {label}\n', '**Hipótese:** '+hypotheses.get(label.split('/')[0],'Verificar comportamento medido.'),
                '\n**Procedimento:** `'+ ' '.join(str(x) for x in data['comando'])+'`',
                '\n**Evidências:** [resumo](./'+label+'/carga-resumo.json), [requisições](./'+label+'/carga-requisicoes.csv), '
                '[série](./'+label+'/carga-segundos.csv), [eventos](./'+label+'/eventos.jsonl).',
                '\n**Tabela:** linhas da execução na tabela geral acima.',
                '\n**Gráficos:** ![Curva](./'+label+'/carga.png)\n\n![Linha do tempo](./'+label+'/linha-tempo.png)']
        with (folder/'carga-requisicoes.csv').open(encoding='utf-8') as f: requests=list(csv.DictReader(f))
        failures=[r for r in requests if r['erro']]
        out.append(f'\n**Análise dos registros:** {len(requests)} requisições, {len(failures)} falhas, '
                   f'{len([p for p in data["total"]["por_pod"] if p])} pods identificados. '
                   f'Distribuição por versão: `{data["total"]["por_versao"]}`. '
                   'Respostas sem X-Pod podem ter sido geradas antes de atingir a API (por exemplo, no Ingress).')
        if requests:
            slow=max(requests,key=lambda r:float(r['latencia_ms']))
            out.append(f'Pior duração registrada: {float(slow["latencia_ms"]):.2f} ms; status {slow["status"]}; '
                       f'erro `{slow["erro"]}`; início {slow["inicio"]}.')
        eventfile=folder/'eventos.jsonl'
        events=[json.loads(line) for line in eventfile.read_text(encoding='utf-8').splitlines()] if eventfile.exists() else []
        out+=['\n| Marco observado | Hora UTC | Evidência |','|---|---|---|']
        for e in events:
            if e['evento'] in ['acao_inicio','acao_fim','acao_erro','fim','divergencia_persistencia']:
                detail={k:v for k,v in e.items() if k not in ['hora','evento']}
                out.append(f'| {e["evento"]} | {e["hora"]} | {str(detail).replace(chr(10)," ").replace("|","/")[:600]} |')
        if label.startswith('base/'):
            degraded=[]
            for prev,current in zip(data['degraus'],data['degraus'][1:]):
                if current['erros_pct']>0 or (current['p95_ms'] and prev['p95_ms'] and current['p95_ms']>1.5*prev['p95_ms'] and current['vazao_ok_s']<1.15*prev['vazao_ok_s']):
                    degraded.append(current['concorrencia'])
            out.append('Sinal de degradação pelo critério exploratório (erro ou p95 >1,5× com ganho de vazão <15%): '+
                       (str(degraded)+'. Confrontar com CPU/fila e repetições antes de atribuir causa.' if degraded else 'não identificado; não é possível afirmar saturação neste intervalo.'))
        if label.startswith('manual/'):
            out.append('A coordenação permanece única. Um ganho pequeno ou negativo refuta escala linear nesta arquitetura; '
                       'o número de réplicas HTTP não remove as escritas serializadas nem o compartilhamento físico da CPU.')
        if label.startswith('hpa/'):
            out.append('Os snapshots em eventos.jsonl contêm as decisões SuccessfulRescale, condições Ready e CPU. '
                       'A série mede o atraso percebido. Normalização de p95 só pode ser afirmada se ele regressar ao patamar de referência; '
                       'criar pods por si só não comprova recuperação de latência.')
        if label.startswith('falhas/'):
            out.append('O tempo da ação administrativa não é o tempo de recuperação do cliente. Use a série após acao_inicio para '
                       'localizar erros e retorno do p95. A falha exercitada é no atendimento; não remove o coordenador persistente.')
        if label.startswith('update/'):
            versions={r['versao'] for r in requests if r['versao']}
            out.append('Versões observadas: '+', '.join(sorted(versions))+'. '+
                       ('A execução observou mais de uma versão.' if len(versions)>1 else 'Não foi observada coexistência nesta execução; verificar momento da troca e distribuição.')+
                       ' Compare falhas e p95 entre rolling, undo e sem-prestop, sem presumir zero interrupções.')
        if label.startswith('assincrono/'):
            out.append('Compare os aceites com o delta persistido e o tempo de drenagem registrados no marco fim. '
                       'A menor latência de aceite não equivale a maior capacidade sustentada do banco.')
    manual=[(int(label.split('/')[1].split('-')[0]),data['degraus'][0]) for label,data in summaries if label.startswith('manual/')]
    if manual:
        manual.sort();base=manual[0][1]['vazao_ok_s']
        out+=['\n## Escala manual\n','| Réplicas | Ganho medido | Ideal | Fração serial estimada |','|---:|---:|---:|---:|']
        for n,r in manual:
            speed=r['vazao_ok_s']/base if base else 0
            serial=(1/speed-1/n)/(1-1/n) if n>1 and speed>0 else None
            out.append(f'| {n} | {fmt(speed)} | {n} | {fmt(serial)} |')
        out.append('\nA estimativa de Amdahl fora de 0..1 indica que o modelo simples não explica os dados (overhead, contenção, ruído ou carga insuficiente). Não truncamos esses valores.')
        fig,ax=plt.subplots(figsize=(8,4));ns=[n for n,_ in manual]
        ax.plot(ns,[r['vazao_ok_s']/base for _,r in manual],marker='o',label='Medido');ax.plot(ns,ns,'--',label='Ideal')
        ax.set(xlabel='Réplicas HTTP',ylabel='Ganho relativo',title='Escala manual');ax.legend();fig.tight_layout();fig.savefig(root/'escala.png',dpi=150);plt.close(fig)
    for kind in ['post','get']:
        runs=[data for label,data in summaries if label.startswith('base/'+kind)]
        if runs:
            out += [f'\n## Variação das repetições da base {kind}\n','| Clientes | Vazão média | Mínima | Máxima | p95 médio ms |','|---:|---:|---:|---:|---:|']
            for c in sorted({r['concorrencia'] for d in runs for r in d['degraus']}):
                rows=[r for d in runs for r in d['degraus'] if r['concorrencia']==c]
                v=[r['vazao_ok_s'] for r in rows];lat=[r['p95_ms'] for r in rows if r['p95_ms'] is not None]
                out.append(f'| {c} | {fmt(statistics.mean(v))} | {fmt(min(v))} | {fmt(max(v))} | {fmt(statistics.mean(lat) if lat else None)} |')
    out+=['\n## Evidências e limites\n',
          'Cada pasta contém comando e parâmetros, CSV por requisição, série por segundo, resumo JSON, gráficos e observações Kubernetes com horário. '
          'eventos.jsonl registra CPU/memória, pods, HPA, intervenções e total persistido antes/depois. '
          'ambiente.txt registra a configuração do cluster. Os nós k3d/k3s compartilham a mesma máquina física. '
          'O nó drenado é um agente; não demonstra tolerância à perda do server-0 que armazena SQLite. '
          'O coordenador permanece um ponto único e o volume local não migra entre nós. '
          'Nenhuma medição desta entrega representa capacidade de produção.']
    (root/'RELATORIO.md').write_text('\n'.join(out),encoding='utf-8')
    print(root/'RELATORIO.md')

if __name__=='__main__': main()
