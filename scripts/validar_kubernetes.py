"""Integração real pelo Ingress, sem acessar o banco pessoal."""
import concurrent.futures
import datetime as dt
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

BASE='http://127.0.0.1:8088'
def request(path,body=None):
    data=None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(BASE+path,data=data,headers={'Content-Type':'application/json'}),timeout=15) as r:
        return r.status,dict(r.headers),json.load(r)
def wait(check,seconds=30):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=check()
        if value:return value
        time.sleep(.2)
    raise AssertionError('Condição não atingida no prazo')
def main():
    stamp=dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')
    ident='INTEGRACAO-'+uuid.uuid4().hex[:10]
    result={'inicio':stamp,'identificador':ident,'servicos':{}}
    bodies={'manancial':dict(area=ident,timestamp=stamp,percentual_ocupado=10,nivel_agua_m=2,temperatura_agua_c=20),
            'inversao-termica':dict(estacao=ident,timestamp=stamp,umidade=20,temperatura_c=35,vento_km_h=2)}
    for service in ['manancial','alagamento','inversao-termica']:
        response=urllib.request.urlopen(BASE+'/'+service+'/tempo-real',timeout=20)
        def read_event():
            for line in response:
                if line.startswith(b'data:'):
                    data=json.loads(line[5:])
                    if ident in [data.get('area'),data.get('estacao'),data.get('ponto_id')]:return data
            raise AssertionError('SSE encerrado sem evento')
        with concurrent.futures.ThreadPoolExecutor(1) as pool:
            future=pool.submit(read_event)
            try:
                posts=[]
                if service=='alagamento':
                    for kind,unit,value in [('nivel_corrego','cm',210),('chuva','mm',60),('velocidade_agua','m/s',2)]:
                        posts.append(request('/alagamento/leituras',dict(sensor_id=kind+'-teste',ponto_id=ident,tipo=kind,unidade=unit,valor=value,timestamp=stamp)))
                else: posts.append(request('/'+service+'/leituras',bodies[service]))
                assert all(p[0]==202 for p in posts)
                event=future.result(timeout=20)
                assert event.get('evento_id')
                query={'manancial':'area','alagamento':'ponto_id','inversao-termica':'estacao'}[service]
                history=wait(lambda:request('/'+service+'/historico?'+query+'='+ident)[2]['leituras'])
                assert history[0]['evento_id']==event['evento_id']
                if service=='alagamento':
                    assert event['status']=='completa' and history[0]['chuva_mm']==60
                alerts=request('/'+service+'/alertas')[2]['alertas']
                assert alerts, 'Alerta recente ausente'
                historical=wait(lambda:request('/'+service+'/alertas-historico?'+query+'='+ident)[2]['alertas'])
                assert historical
                result['servicos'][service]={'post':202,'sse':True,'historico':True,'alertas':True,
                    'evento_id':event['evento_id'],'headers':posts[0][1]}
            finally: response.close()
    # Uma cópia diferente precisa acessar a mesma leitura, depois de escalar o atendimento.
    context=['kubectl','--context','kind-aps-experimentos','-n','aps-lab']
    subprocess.run(context+['scale','deployment/manancial','--replicas=2'],check=True)
    subprocess.run(context+['rollout','status','deployment/manancial','--timeout=120s'],check=True)
    pods=set()
    for _ in range(30):
        status,headers,body=request('/manancial/historico?area='+ident)
        assert body['total']==1
        pods.add(headers.get('X-pod',headers.get('X-Pod','')))
    assert len(pods)>=2, f'Ingress não distribuiu entre duas réplicas: {pods}'
    result['distribuicao']=sorted(pods)
    # Restart do coordenador recupera volume; nenhuma exclusão do PVC.
    subprocess.run(context+['delete','pod','manancial-estado-0'],check=True)
    subprocess.run(context+['rollout','status','statefulset/manancial-estado','--timeout=180s'],check=True)
    def recovered():
        try:return request('/manancial/historico?area='+ident)[2]['total']==1
        except OSError:return False
    wait(recovered,60)
    result['persistencia_apos_restart']=True
    folder=Path('resultados/integracao');folder.mkdir(parents=True,exist_ok=True)
    (folder/'validacao.json').write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
    print(json.dumps(result,indent=2,ensure_ascii=False))

if __name__=='__main__':main()
