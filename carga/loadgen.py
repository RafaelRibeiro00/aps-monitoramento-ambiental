"""Gerador autoral em laço fechado. Somente alvos locais do laboratório APS."""
import argparse
import concurrent.futures
import csv
import datetime as dt
import ipaddress
import json
import math
import queue
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")


def percentil(values, p):
    if not values:
        return None
    data = sorted(values)
    return data[max(0, math.ceil(len(data) * p / 100) - 1)]


def resumo(rows, seconds):
    lat = [r['latencia_ms'] for r in rows]
    good = sum(r['erro'] == '' for r in rows)
    return dict(requisicoes=len(rows), sucessos=good, erros=len(rows)-good,
                erros_pct=100*(len(rows)-good)/len(rows) if rows else 0,
                vazao_ok_s=good/seconds if seconds else 0,
                p50_ms=percentil(lat, 50), p95_ms=percentil(lat, 95),
                p99_ms=percentil(lat, 99), media_ms=sum(lat)/len(lat) if lat else None,
                por_pod=dict(Counter(r['pod'] for r in rows)),
                por_versao=dict(Counter(r['versao'] for r in rows)),
                por_status=dict(Counter(str(r['status']) for r in rows)),
                por_erro=dict(Counter(r['erro'] for r in rows if r['erro'])))


def corpo(servico, worker, seq, run):
    timestamp = utc()
    ident = f'CARGA-{run}-{worker}-{seq}'
    if servico == 'manancial':
        return dict(area=ident, timestamp=timestamp, percentual_ocupado=40+seq%40,
                    nivel_agua_m=2+seq%10, temperatura_agua_c=20+seq%10)
    if servico == 'inversao-termica':
        return dict(estacao=ident, timestamp=timestamp, umidade=40+seq%40,
                    temperatura_c=20+seq%10, vento_km_h=10+seq%10)
    # Um trabalhador envia os três sensores sequencialmente para o mesmo ponto.
    kind, unit, value = [('nivel_corrego','cm',100),('chuva','mm',10),
                         ('velocidade_agua','m/s',2)][seq % 3]
    return dict(sensor_id=f'{kind}-{worker}', ponto_id=f'CARGA-{run}-{worker}-{seq//3}',
                tipo=kind, unidade=unit, valor=value, timestamp=timestamp)


def validar_local(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'http' or not parsed.hostname or parsed.username:
        raise ValueError('Use uma URL HTTP local, sem credenciais.')
    addresses = socket.getaddrinfo(parsed.hostname, parsed.port or 80)
    if not all(ipaddress.ip_address(a[4][0]).is_loopback for a in addresses):
        raise ValueError('O gerador aceita somente localhost/loopback do cluster próprio.')


class SemRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def executar(args):
    validar_local(args.url)
    prefix = Path(args.saida)
    prefix.parent.mkdir(parents=True, exist_ok=True)
    rows, series = [], []
    results = queue.Queue()
    stop = threading.Event()
    run = str(time.time_ns())
    start_wall = utc()
    start = time.perf_counter()
    steps = args.degraus or [args.concorrencia]
    duration = args.duracao * len(steps)
    maxworkers = max(steps)
    prepared = queue.Queue()
    launch = threading.Event()
    def active(elapsed):
        idx = min(int(elapsed / args.duracao), len(steps)-1)
        count = steps[idx]
        if args.rampa:
            count = min(count, 1 + int((count-1)*min(elapsed/args.rampa, 1)))
        return idx, count

    def worker(wid):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), SemRedirect())
        prepared.put(wid)
        launch.wait()
        seq = 0
        while not stop.is_set():
            elapsed = time.perf_counter()-start
            if elapsed >= duration:
                return
            idx, count = active(elapsed)
            if wid >= count:
                stop.wait(.02)
                continue
            body = None
            if args.metodo == 'POST':
                obj = json.loads(args.corpo) if args.corpo else corpo(args.servico, wid, seq, run)
                body = json.dumps(obj).encode()
            request = urllib.request.Request(args.url, data=body, method=args.metodo,
                headers={'Content-Type':'application/json', 'Connection':'close'})
            begin = time.perf_counter()
            row = dict(inicio=utc(), inicio_s=begin-start, trabalhador=wid,
                       degrau=idx, concorrencia=count, status=0, pod='', versao='',
                       backend='', erro='', bytes=0, evento_id='')
            try:
                with opener.open(request, timeout=args.timeout) as response:
                    payload = response.read()
                    row.update(status=response.status, pod=response.headers.get('X-Pod',''),
                               versao=response.headers.get('X-Versao',''),
                               backend=response.headers.get('X-Backend-Pod',''), bytes=len(payload))
                    if args.metodo == 'POST':
                        try: row['evento_id'] = json.loads(payload).get('evento_id','')
                        except (ValueError, AttributeError): pass
            except urllib.error.HTTPError as e:
                row.update(status=e.code, erro=f'http_{e.code}', bytes=len(e.read()),
                           pod=e.headers.get('X-Pod',''), versao=e.headers.get('X-Versao',''))
                e.close()
            except (TimeoutError, socket.timeout):
                row['erro'] = 'timeout'
            except urllib.error.URLError as e:
                row['erro'] = 'timeout' if isinstance(e.reason, (TimeoutError, socket.timeout)) else 'conexao'
            except (OSError, ValueError) as e:
                row['erro'] = type(e).__name__
            end = time.perf_counter()
            row.update(fim_s=end-start, latencia_ms=(end-begin)*1000,
                       aquecimento=(begin-start-idx*args.duracao)<args.aquecimento)
            results.put(row)
            seq += 1

    pool = concurrent.futures.ThreadPoolExecutor(max_workers=maxworkers)
    futures = [pool.submit(worker, n) for n in range(maxworkers)]
    for _ in futures: prepared.get(timeout=30)
    start_wall = utc()
    start = time.perf_counter()
    launch.set()
    rawfile = prefix.with_name(prefix.name+'-requisicoes.csv')
    fields = ['inicio','inicio_s','fim_s','trabalhador','degrau','concorrencia','status',
              'pod','versao','backend','erro','bytes','evento_id','latencia_ms','aquecimento']
    next_report = start+1
    last_count = 0
    try:
        with rawfile.open('w', newline='', encoding='utf-8') as output:
            writer = csv.DictWriter(output, fieldnames=fields)
            writer.writeheader()
            while not all(f.done() for f in futures) or not results.empty():
                try:
                    row = results.get(timeout=.05)
                    rows.append(row); writer.writerow(row)
                except queue.Empty: pass
                now = time.perf_counter()
                if now >= next_report:
                    batch = rows[last_count:]; last_count = len(rows)
                    summary = resumo(batch, now-(next_report-1))
                    idx, count = active(min(now-start, duration-.001))
                    series.append(dict(hora=utc(), segundo=now-start, concorrencia=count,
                                       **{k:v for k,v in summary.items() if not isinstance(v,dict)},
                                       pods=len({r['pod'] for r in batch if r['pod']})))
                    print(f'{utc()} clientes={count} ok/s={summary["vazao_ok_s"]:.1f} '
                          f'erros={summary["erros"]} p95={summary["p95_ms"]} ms', flush=True)
                    output.flush(); next_report = now+1
    finally:
        stop.set(); pool.shutdown(wait=True)
    for f in futures: f.result()
    if last_count < len(rows):
        batch = rows[last_count:]
        span = max(time.perf_counter()-(next_report-1), .001)
        summary = resumo(batch, span)
        series.append(dict(hora=utc(), segundo=time.perf_counter()-start, concorrencia=0,
                           **{k:v for k,v in summary.items() if not isinstance(v,dict)},
                           pods=len({r['pod'] for r in batch if r['pod']})))
    with prefix.with_name(prefix.name+'-segundos.csv').open('w',newline='',encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=list(series[0]) if series else ['hora'])
        writer.writeheader(); writer.writerows(series)
    summaries=[]
    for idx, count in enumerate(steps):
        measured=[r for r in rows if r['degrau']==idx and not r['aquecimento']]
        end=max([r['fim_s'] for r in measured]+[(idx+1)*args.duracao])
        seconds=end-(idx*args.duracao+args.aquecimento)
        summaries.append(dict(degrau=idx, concorrencia=count, segundos_medidos=seconds,
                              **resumo(measured,seconds)))
    document=dict(inicio=start_wall, fim=utc(), parametros=vars(args),
                  comando=__import__('sys').argv, duracao_real_s=time.perf_counter()-start,
                  criterio='Latencias incluem falhas; aquecimento excluido somente dos resumos por degrau. '
                           'Janela inclui conclusao das requisicoes iniciadas no degrau.',
                  total=resumo(rows,time.perf_counter()-start), degraus=summaries)
    prefix.with_name(prefix.name+'-resumo.json').write_text(json.dumps(document,indent=2),encoding='utf-8')
    print(json.dumps(document['degraus'],indent=2), flush=True)
    return document


def parser():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('url')
    p.add_argument('-c','--concorrencia',type=int,default=8)
    p.add_argument('-d','--duracao',type=float,default=20)
    p.add_argument('--timeout',type=float,default=5)
    p.add_argument('--aquecimento',type=float,default=5)
    p.add_argument('--rampa',type=float,default=0,help='Segundos ate a concorrencia maxima')
    p.add_argument('--degraus',type=lambda s:[int(x) for x in s.split(',')])
    p.add_argument('--metodo',choices=['GET','POST'],default='GET')
    p.add_argument('--servico',choices=['manancial','alagamento','inversao-termica'],default='manancial')
    p.add_argument('--corpo',help='Objeto JSON fixo opcional; normalmente use o gerador de sensores')
    p.add_argument('--saida',default='resultados/manual/execucao')
    return p


def main():
    p=parser(); a=p.parse_args()
    if a.concorrencia<1 or a.concorrencia>256 or a.timeout<=0 or a.duracao<=a.aquecimento or a.aquecimento<0:
        p.error('Use concorrencia 1..256, timeout positivo e 0 <= aquecimento < duracao.')
    if a.degraus and (a.rampa or min(a.degraus)<1 or max(a.degraus)>256):
        p.error('Degraus devem ser 1..256 e nao podem ser combinados com rampa.')
    if a.rampa<0: p.error('Rampa deve ser nao negativa.')
    executar(a)


if __name__=='__main__': main()
