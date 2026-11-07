# Cluster k3d/k3s da APS

O laboratório usa **k3d**, que executa a distribuição Kubernetes **k3s** em contêineres Docker.
O cluster chama-se `aps`, o contexto é `k3d-aps`, o namespace é `aps` e o Ingress é
publicado em `http://127.0.0.1:8088`.

## Arquitetura

```text
Cliente → serverlb → Traefik/Ingress → Service → Deployment Java replicável
                                              → Service headless → StatefulSet de estado
                                                                   → fila local + SQLite/PVC
```

- `k3d-aps-server-0`: plano de controle, StatefulSets de estado, Redis e RabbitMQ.
- `k3d-aps-agent-0` e `k3d-aps-agent-1`: pods replicáveis das três APIs.
- `k3d-aps-serverlb`: encaminha a porta 8088 do Windows à porta 80 do Traefik.
- Cada API aponta por DNS para seu único pod de estado: `manancial-estado`,
  `alagamento-estado` ou `inversao-termica-estado`.
- O SQLite e a fila persistente ficam no PVC do respectivo StatefulSet. Excluir o pod não
  apaga os dados. Excluir o cluster pode apagar os volumes locais.

## Arquivos

| Arquivo | Conteúdo |
|---|---|
| `00-namespace.yaml` | namespace `aps` |
| `01-configmap.yaml` | configuração comum, sem credenciais |
| `10-cache-fila.yaml` | Redis e RabbitMQ, ambos com Service e PVC |
| `11-estado.yaml` | três Services headless e três StatefulSets SQLite |
| `20-apis.yaml` | três Deployments, Services e PDBs |
| `30-ingress.yaml` | Ingress único por caminho usando Traefik do k3s |
| `40-hpa.yaml` | HPA do Manancial, 2 a 10 réplicas por CPU |

O Secret verdadeiro não é versionado. `secret.example.yaml` documenta as chaves; o script
cria `aps-segredos` a partir do `.env` local.

## Subir do zero

```powershell
./scripts/preparar_kubernetes.ps1
kubectl get nodes
kubectl get all,ingress,pvc,hpa -n aps -o wide
kubectl top pod -n aps
python scripts/validar_kubernetes.py
```

O script baixa o k3d 5.9.0 para `%LOCALAPPDATA%\APS-tools`, cria um servidor e dois agentes,
constrói e importa as imagens com tag fixa, cria o Secret e aplica `k8s/`.

## Rotas pelo Ingress

```text
POST /manancial/leituras
GET  /manancial/historico
POST /alagamento/leituras
GET  /alagamento/historico
POST /inversao-termica/leituras
GET  /inversao-termica/historico
```

As rotas de SSE, alertas e saúde continuam disponíveis sob os mesmos prefixos.

## Evidências pedidas na aula

```powershell
kubectl get pods -n aps -o wide
kubectl get configmap,secret -n aps
kubectl get ingress,pvc,hpa -n aps
kubectl top pod -n aps
kubectl describe hpa manancial -n aps
```

Para provar balanceamento, consulte o cabeçalho `X-Pod`. Para provar persistência, grave uma
leitura, exclua `manancial-estado-0`, aguarde o StatefulSet e consulte a leitura novamente.

## Escala, falhas e atualização

```powershell
kubectl delete hpa manancial -n aps
kubectl scale deployment/manancial -n aps --replicas=4
kubectl delete pod -n aps <pod-de-atendimento>
kubectl drain k3d-aps-agent-1 --ignore-daemonsets --delete-emptydir-data
kubectl uncordon k3d-aps-agent-1
kubectl set image deployment/manancial -n aps api=aps-manancial:3.0.1
kubectl rollout status deployment/manancial -n aps
kubectl rollout undo deployment/manancial -n aps
```

O `server-0` é um ponto único para os dados neste laboratório. Os testes de falha de nó
devem usar um agente, nunca o servidor que mantém os PVCs do SQLite.
