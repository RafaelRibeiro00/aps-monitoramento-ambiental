"""Gera manifests JSON Kubernetes (também aceitos pelo kubectl como YAML)."""
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parent
NS='aps-lab'
def obj(kind,name,spec=None,api='v1',**extra):
    d={'apiVersion':api,'kind':kind,'metadata':{'name':name,'namespace':NS},**extra}
    if spec is not None: d['spec']=spec
    return d
def env(name,value): return {'name':name,'value':str(value)}
def podenv(): return {'name':'POD_NAME','valueFrom':{'fieldRef':{'fieldPath':'metadata.name'}}}
def service(name,port,selector=None,node=None):
    return obj('Service',name,{'type':'NodePort' if node else 'ClusterIP',
        'selector':{'app':selector or name},'ports':[{'port':port,'targetPort':port,**({'nodePort':node} if node else {})}]})
def save(name,items):
    (ROOT/name).write_text(json.dumps({'apiVersion':'v1','kind':'List','items':items},indent=2),encoding='utf-8')

items=[{'apiVersion':'v1','kind':'Namespace','metadata':{'name':NS}},obj('ServiceAccount','ingress')]
items += [dict(apiVersion='rbac.authorization.k8s.io/v1',kind='ClusterRole',metadata={'name':'aps-ingress'},
    rules=[{'apiGroups':[''],'resources':['services','endpoints','secrets','nodes'],'verbs':['get','list','watch']},
           {'apiGroups':['discovery.k8s.io'],'resources':['endpointslices'],'verbs':['get','list','watch']},
           {'apiGroups':['networking.k8s.io'],'resources':['ingresses','ingressclasses'],'verbs':['get','list','watch']},
           {'apiGroups':['networking.k8s.io'],'resources':['ingresses/status'],'verbs':['update']}]),
    dict(apiVersion='rbac.authorization.k8s.io/v1',kind='ClusterRoleBinding',metadata={'name':'aps-ingress'},
         roleRef={'apiGroup':'rbac.authorization.k8s.io','kind':'ClusterRole','name':'aps-ingress'},
         subjects=[{'kind':'ServiceAccount','name':'ingress','namespace':NS}]),
    dict(apiVersion='networking.k8s.io/v1',kind='IngressClass',metadata={'name':'aps'},spec={'controller':'traefik.io/ingress-controller'})]
items += [obj('Deployment','ingress',{'replicas':1,'selector':{'matchLabels':{'app':'ingress'}},
    'template':{'metadata':{'labels':{'app':'ingress'}},'spec':{'serviceAccountName':'ingress',
      'nodeSelector':{'kubernetes.io/hostname':'aps-experimentos-control-plane'},
      'tolerations':[{'key':'node-role.kubernetes.io/control-plane','operator':'Exists','effect':'NoSchedule'}],
      'containers':[{'name':'ingress','image':'traefik:v3.5.0','args':['--providers.kubernetesingress=true',
        '--providers.kubernetesingress.ingressclass=aps','--entrypoints.web.address=:8080',
        '--entrypoints.web.transport.respondingtimeouts.writetimeout=0','--accesslog=true'],
        'ports':[{'containerPort':8080}], 'resources':{'requests':{'cpu':'50m','memory':'48Mi'},'limits':{'cpu':'500m','memory':'160Mi'}}}]}}},api='apps/v1'),service('ingress',8080,node=30080)]
paths=[]
for svc,port in [('manancial',8081),('alagamento',8082),('inversao-termica',8083)]:
    coord=svc+'-estado'
    pv=obj('PersistentVolume',coord,{'capacity':{'storage':'1Gi'},'accessModes':['ReadWriteOnce'],
        'persistentVolumeReclaimPolicy':'Retain','storageClassName':'',
        'hostPath':{'path':'/var/local/aps-lab/'+svc,'type':'DirectoryOrCreate'},
        'nodeAffinity':{'required':{'nodeSelectorTerms':[{'matchExpressions':[{'key':'kubernetes.io/hostname','operator':'In','values':['aps-experimentos-control-plane']}]}]}}})
    pv['metadata'].pop('namespace');items.append(pv)
    items.append(obj('PersistentVolumeClaim',coord,{'accessModes':['ReadWriteOnce'],'storageClassName':'','volumeName':coord,'resources':{'requests':{'storage':'1Gi'}}}))
    common=[env('APS_HOST','0.0.0.0'),env('APS_PORT',port),env('APS_VERSAO','3.0.0'),podenv(),env('JAVA_TOOL_OPTIONS','-Xms16m -Xmx64m -Xss256k -XX:ActiveProcessorCount=2')]
    probe={'httpGet':{'path':'/health/ready','port':port},'periodSeconds':3,'timeoutSeconds':2,'failureThreshold':3}
    container={'name':'api','image':'aps-'+svc+':3.0.0','imagePullPolicy':'IfNotPresent','ports':[{'containerPort':port}],
        'env':common+[env('APS_DB','/app/dados/aps.db')],
        'readinessProbe':probe,'livenessProbe':{'httpGet':{'path':'/health/live','port':port},'periodSeconds':10,'timeoutSeconds':3,'failureThreshold':6},
        'startupProbe':{'httpGet':{'path':'/health/live','port':port},'periodSeconds':3,'failureThreshold':40},
        'resources':{'requests':{'cpu':'100m','memory':'96Mi'},'limits':{'cpu':'500m','memory':'256Mi'}},
        'volumeMounts':[{'name':'dados','mountPath':'/app/dados'}]}
    items.append(obj('StatefulSet',coord,{'serviceName':coord,'replicas':1,'selector':{'matchLabels':{'app':coord}},
      'template':{'metadata':{'labels':{'app':coord,'camada':'estado'}},'spec':{
        'nodeSelector':{'kubernetes.io/hostname':'aps-experimentos-control-plane'},
        'tolerations':[{'key':'node-role.kubernetes.io/control-plane','operator':'Exists','effect':'NoSchedule'}],
        'terminationGracePeriodSeconds':40,'securityContext':{'fsGroup':10001},
        'initContainers':[{'name':'permissoes','image':'busybox:1.37','command':['sh','-c','chown 10001:10001 /dados'],
                           'volumeMounts':[{'name':'dados','mountPath':'/dados'}]}],
        'containers':[container], 'volumes':[{'name':'dados','persistentVolumeClaim':{'claimName':coord}}]}}},api='apps/v1'))
    items.append(service(coord,port))
    front=json.loads(json.dumps(container));front.pop('volumeMounts')
    front['env']=common+[env('APS_COORDENADOR',f'http://{coord}:{port}')]
    front['resources']={'requests':{'cpu':'100m','memory':'80Mi'},'limits':{'cpu':'250m','memory':'160Mi'}}
    front['lifecycle']={'preStop':{'exec':{'command':['sh','-c','sleep 5']}}}
    items.append(obj('Deployment',svc,{'replicas':1,'revisionHistoryLimit':5,
      'strategy':{'type':'RollingUpdate','rollingUpdate':{'maxSurge':1,'maxUnavailable':0}},
      'selector':{'matchLabels':{'app':svc}},'template':{'metadata':{'labels':{'app':svc,'camada':'atendimento'}},
      'spec':{'terminationGracePeriodSeconds':30,'containers':[front],
        'topologySpreadConstraints':[{'maxSkew':1,'topologyKey':'kubernetes.io/hostname','whenUnsatisfiable':'ScheduleAnyway','labelSelector':{'matchLabels':{'app':svc}}}]}}},api='apps/v1'))
    items.append(service(svc,port))
    items.append(obj('PodDisruptionBudget',svc,{'minAvailable':1,'selector':{'matchLabels':{'app':svc}}},api='policy/v1'))
    paths.append({'path':'/'+svc,'pathType':'Prefix','backend':{'service':{'name':svc,'port':{'number':port}}}})
items.append(obj('Ingress','apis',{'ingressClassName':'aps','rules':[{'http':{'paths':paths}}]},api='networking.k8s.io/v1'))
save('aplicacao.json',items)
save('hpa.json',[obj('HorizontalPodAutoscaler','manancial',{
    'scaleTargetRef':{'apiVersion':'apps/v1','kind':'Deployment','name':'manancial'},'minReplicas':2,'maxReplicas':10,
    'metrics':[{'type':'Resource','resource':{'name':'cpu','target':{'type':'Utilization','averageUtilization':60}}}],
    'behavior':{'scaleDown':{'stabilizationWindowSeconds':60}}},api='autoscaling/v2')])
print('Manifests gerados em',ROOT)
