"""Experimentos locais reproduzíveis. Usa somente o contexto kind-aps-experimentos."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
CONTEXT='kind-aps-experimentos'
NAMESPACE='aps-lab'
BASE='http://127.0.0.1:8088'
OUT=ROOT/'resultados'

def now(): return dt.datetime.now(dt.timezone.utc).isoformat()
def k(*args,check=True):
    p=subprocess.run(['kubectl','--context',CONTEXT,'-n',NAMESPACE,*args],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=240 if args[0] in ('drain','rollout') else 30)
    if check and p.returncode: raise RuntimeError(p.stderr)
    return p.stdout+p.stderr
def event(folder,name,**values):
    with (folder/'eventos.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps(dict(hora=now(),evento=name,**values),ensure_ascii=False)+'\n')
def observe(folder,stop):
    while not stop.is_set():
        for args in [('top','pods','--containers'),('get','pods,hpa','-o','json'),('get','events','-o','json')]:
            try: event(folder,'observacao',comando=list(args),saida=k(*args,check=False))
            except subprocess.TimeoutExpired: event(folder,'observacao_erro',comando=list(args),erro='timeout_kubectl')
        stop.wait(3)
def ready(name='deployment/manancial'):
    return k('rollout','status',name,'--timeout=180s')
def replicas(n):
    k('delete','hpa','manancial','--ignore-not-found')
    k('scale','deployment/manancial',f'--replicas={n}')
    ready()
def metricas():
    with urllib.request.urlopen(BASE+'/manancial/metricas',timeout=5) as r: return json.load(r)
def total():
    with urllib.request.urlopen(BASE+'/manancial/historico?limite=1',timeout=10) as r: return json.load(r)['total']
def drain_queue(folder):
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        try: m=metricas()
        except OSError as e:
            event(folder,'fila_indisponivel',erro=str(e));time.sleep(1);continue
        event(folder,'fila',**m)
        if m['fila_pendente']==0: return
        time.sleep(1)
    raise RuntimeError('Fila não esvaziou em 180s; resultado de capacidade incompleto.')
def run(label,route='/manancial/leituras',duration=30,c=16,steps=None,ramp=None,action=None,action_at=12):
    folder=OUT/label;folder.mkdir(parents=True,exist_ok=True)
    drain_queue(folder)
    before=total()
    command=[sys.executable,str(ROOT/'carga/loadgen.py'),BASE+route,'-c',str(c),'-d',str(duration),
        '--aquecimento','5','--timeout','5','--metodo','POST' if route.endswith('/leituras') else 'GET',
        '--saida',str(folder/'carga')]
    if steps: command+=['--degraus',steps]
    if ramp: command+=['--rampa',str(ramp)]
    event(folder,'inicio',comando=command,leituras_antes=before)
    stop=threading.Event(); observer=threading.Thread(target=observe,args=(folder,stop),daemon=True);observer.start()
    errors=[]
    def act():
        if stop.wait(action_at): return
        try:
            event(folder,'acao_inicio'); result=action(folder);event(folder,'acao_fim',resultado=result)
        except Exception as e:
            errors.append(str(e));event(folder,'acao_erro',erro=str(e))
    actor=threading.Thread(target=act,daemon=True) if action else None
    if actor: actor.start()
    try:
        with (folder/'console.txt').open('w',encoding='utf-8') as log:
            p=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,cwd=ROOT)
        if p.returncode: raise RuntimeError(f'Gerador falhou: {folder}')
        if actor: actor.join(timeout=190)
        if actor and actor.is_alive(): raise RuntimeError('Ação do experimento não terminou.')
    finally:
        stop.set();observer.join(timeout=30)
    drain_queue(folder)
    after=total()
    summary=json.loads((folder/'carga-resumo.json').read_text())
    event(folder,'fim',leituras_depois=after,delta_persistido=after-before,
          aceites=summary['total']['sucessos'],erros_acoes=errors)
    if route.endswith('/leituras') and not errors and after-before!=summary['total']['sucessos']:
        event(folder,'divergencia_persistencia',nota='Timeout pode ter sido aceito; correlacionar evento_id e logs.')
    if errors: raise RuntimeError('; '.join(errors))
    print(f'Concluído: {label}',flush=True)
    return summary

def main():
    global OUT
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--etapa',choices=['base','manual','hpa','falhas','update','assincrono','tudo'],default='tudo')
    p.add_argument('--saida',default='resultados/execucao-'+dt.datetime.now().strftime('%Y%m%d-%H%M%S'))
    a=p.parse_args();OUT=ROOT/a.saida;OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'ambiente.txt').write_text(k('get','nodes','-o','wide')+'\n'+k('get','deploy,statefulset,hpa','-o','yaml')+
        '\n'+subprocess.check_output(['docker','info','--format','CPU={{.NCPU}} RAM={{.MemTotal}}'],text=True)+
        '\nGerador Windows na mesma máquina física. Contexto '+CONTEXT,encoding='utf-8')
    def enabled(s): return a.etapa in (s,'tudo')
    if enabled('base'):
        replicas(1)
        for route,tag in [('/manancial/leituras','post'),('/manancial/historico?limite=20&ordem=desc','get')]:
            for rep in range(1,4):
                run(f'base/{tag}-{rep}',route,duration=20,steps='1,2,4,8,16,32,64')
    if enabled('manual'):
        for n in [1,2,4,8]:
            replicas(n);run(f'manual/{n}-replicas',c=32,duration=35)
    if enabled('hpa'):
        replicas(2);k('apply','-f',str(ROOT/'k8s/hpa.json'))
        run('hpa/rampa',c=64,duration=180,ramp=120)
        folder=OUT/'hpa/rampa'
        for _ in range(24):
            event(folder,'reducao',saida=k('get','hpa,pods','-o','json'));time.sleep(5)
    if enabled('falhas'):
        replicas(4)
        def delete(folder):
            pods=json.loads(k('get','pods','-l','app=manancial','-o','json'))['items']
            name=next(x['metadata']['name'] for x in pods if x['status']['phase']=='Running')
            event(folder,'pod_escolhido',pod=name)
            return k('delete','pod',name,'--wait=false')
        run('falhas/pod',duration=60,c=24,action=delete)
        node='aps-experimentos-worker'
        # PDB minAvailable=1 de outros serviços também participa da drenagem.
        for service in ['alagamento','inversao-termica']:
            k('scale','deployment/'+service,'--replicas=2');ready('deployment/'+service)
        def drain(folder): return k('drain',node,'--ignore-daemonsets','--delete-emptydir-data','--timeout=120s')
        try: run('falhas/no',duration=100,c=24,action=drain)
        finally:
            k('uncordon',node)
            for service in ['alagamento','inversao-termica']:
                k('scale','deployment/'+service,'--replicas=1')
    if enabled('update'):
        replicas(4)
        def update(folder):
            patch={'spec':{'template':{'spec':{'containers':[{'name':'api','image':'aps-manancial:3.0.1',
                    'env':[{'name':'APS_VERSAO','value':'3.0.1'}]}]}}}}
            patchfile=folder/'patch.json';patchfile.write_text(json.dumps(patch))
            result=k('patch','deployment','manancial','--type=strategic','--patch-file',str(patchfile))
            return result+ready()
        run('update/rolling',duration=70,c=24,action=update)
        def undo(folder): return k('rollout','undo','deployment/manancial')+ready()
        run('update/undo',duration=70,c=24,action=undo)
        (OUT/'update/history.txt').write_text(k('rollout','history','deployment/manancial'),encoding='utf-8')
        k('patch','deployment','manancial','--type=json','-p','[{"op":"remove","path":"/spec/template/spec/containers/0/lifecycle"}]')
        ready()
        try: run('update/sem-prestop',duration=70,c=24,action=update)
        finally:
            k('apply','-f',str(ROOT/'k8s/aplicacao.json'));ready()
    if enabled('assincrono'):
        replicas(1)
        for mode in ['sincrona','assincrona']:
            k('set','env','statefulset/manancial-estado','APS_ESCRITA='+mode)
            ready('statefulset/manancial-estado');ready()
            run('assincrono/'+mode,duration=40,c=16)
    replicas(2)
    print('Resultados:',OUT,flush=True)

if __name__=='__main__': main()
