# Validação atual — k3d/k3s

Data: 07/11/2026  
Cluster: `aps`  
Contexto: `k3d-aps`  
Namespace: `aps`  
Ingress: `http://127.0.0.1:8088`

## Resultado

- Três nós Kubernetes `Ready`: um servidor e dois agentes.
- Duas réplicas de cada API distribuídas entre os dois agentes.
- Três pods de estado no servidor, cada um com seu SQLite em PVC de 1 GiB.
- Redis e RabbitMQ no servidor, com Service, probes e PVC.
- Traefik roteando os três prefixos pelo mesmo Ingress.
- HPA do Manancial ativo: mínimo 2, máximo 10, alvo de CPU 60%.
- `kubectl top pod -n aps` respondendo pelo metrics-server.

## Teste integrado aprovado

O arquivo `resultados/integracao/validacao.json` registra a execução real. Para os três
domínios foram aprovados:

1. `POST /leituras` com HTTP 202;
2. recebimento do mesmo evento por SSE em `/tempo-real`;
3. consulta do registro persistido em `/historico`;
4. alerta recente em `/alertas`;
5. alerta persistido em `/alertas-historico`.

Também foi comprovado que o Ingress alternou o Manancial entre dois pods e que a leitura
continuou disponível depois da exclusão e recriação de `manancial-estado-0`. Isso valida o
PVC: o pod foi substituído, mas o banco não foi perdido.

## Comandos para conferência

```powershell
kubectl get nodes -o wide
kubectl get pods -n aps -o wide
kubectl get ingress,pvc,hpa -n aps
kubectl top pod -n aps
python scripts/validar_kubernetes.py
```

## Observação de arquitetura

Redis e RabbitMQ atendem ao requisito de cache e fila de infraestrutura no cluster. O fluxo
transacional atual das APIs continua usando a fila persistente implementada na aplicação e
o SQLite de cada StatefulSet; não se declara falsamente que os dados já passam por Redis ou
RabbitMQ.
