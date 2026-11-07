# APS — Sistema de Monitoramento Ambiental

O projeto possui três APIs Java 17: **Manancial**, **Alagamento** e **Inversão Térmica**.
O ambiente distribuído principal usa Kubernetes k3s criado localmente pelo k3d.

## Cluster principal

- Cluster: `aps`
- Contexto kubectl: `k3d-aps`
- Namespace: `aps`
- Nós: `k3d-aps-server-0`, `k3d-aps-agent-0` e `k3d-aps-agent-1`
- Entrada única: `http://127.0.0.1:8088`
- Ingress: Traefik fornecido pelo k3s
- Métricas: metrics-server fornecido pelo k3s
- Armazenamento: PVCs `local-path`

```powershell
./scripts/preparar_kubernetes.ps1
python scripts/validar_kubernetes.py
```

Detalhes completos: [k8s/README.md](k8s/README.md).

## Organização da aplicação

Cada domínio possui um Deployment replicável e um StatefulSet de estado. Os pods de
atendimento não abrem o SQLite: eles chamam o Service headless do respectivo estado.
O StatefulSet centraliza agrupamento, alertas, fila persistente e banco SQLite no PVC.

O cluster também contém Redis como cache e RabbitMQ como fila de infraestrutura, ambos
com Service, probes, limites de recurso e armazenamento persistente.

## Rotas

| Método | Manancial | Alagamento | Inversão térmica |
|---|---|---|---|
| POST | `/manancial/leituras` | `/alagamento/leituras` | `/inversao-termica/leituras` |
| GET | `/manancial/historico` | `/alagamento/historico` | `/inversao-termica/historico` |
| SSE | `/manancial/tempo-real` | `/alagamento/tempo-real` | `/inversao-termica/tempo-real` |
| GET | `/manancial/alertas` | `/alagamento/alertas` | `/inversao-termica/alertas` |
| GET | `/manancial/alertas-historico` | `/alagamento/alertas-historico` | `/inversao-termica/alertas-historico` |

Saúde interna: `/health/live` e `/health/ready`.

## Docker Compose

O Compose continua disponível apenas para desenvolvimento sem Kubernetes:

```powershell
docker compose up -d --build --wait
```

No laboratório e na apresentação, use k3d/k3s. No Docker Desktop serão exibidos quatro
contêineres: `k3d-aps-server-0`, dois agentes e `k3d-aps-serverlb`. Os contêineres das APIs
ficam dentro dos nós e são visualizados com `kubectl get pods -n aps -o wide`.

## Testes e experimentos

```powershell
mvn -B -ntp verify
python -m unittest carga.test_loadgen -v
python scripts/validar_kubernetes.py
python scripts/experimentos.py --etapa tudo
```

O gerador registra latência, p50, p95, p99, vazão, erros, `X-Pod`, `X-Versao`, CSV por
requisição, série por segundo e resumo JSON.

## Backup da implantação anterior

A implementação Kind e suas evidências históricas foram preservadas em
`backup/kind-20261107/`. Ela não é usada pelos scripts ativos.
