# Laboratório das etapas 4 e 5

Este ambiente é independente do Compose e do banco de sensores existente. Entrada:
`http://127.0.0.1:8088`, com os mesmos prefixos das três APIs. Contexto obrigatório:
`kind-aps-experimentos`; namespace `aps-lab`. São três nós virtuais na mesma máquina física.

## Arquitetura e limite da escala

Ingress Traefik → Service → Deployment Java replicável → coordenador Java StatefulSet
→ fila persistente e SQLite no volume. Cada domínio possui seu coordenador e banco próprios
no laboratório. Os arquivos antigos do Windows não são montados nem migrados.

`Replica.java` valida a ingestão e encaminha as rotas para o coordenador fixo do domínio.
Não existe afinidade dependente de um navegador. Os três POSTs do alagamento, enviados
por conexões distintas, chegam ao mesmo agrupador. Consultas e SSE enxergam a mesma fonte.
`X-Pod` identifica o atendimento; `X-Backend-Pod` identifica a coordenação; `X-Versao`
identifica a versão do atendimento. Não se deve apresentar o número de réplicas HTTP
como número de bancos ou agrupadores independentes.

Esta separação torna possível variar o atendimento sem quebrar o contrato existente.
Ela **não remove o gargalo central**: adicionar réplicas pode não melhorar a vazão, e essa
é uma hipótese a testar. Para distribuir também a coordenação seriam necessários banco
servidor, fila compartilhada, agrupamento distribuído e distribuição de eventos SSE.
Isso não está implementado por estes manifests.

O volume local do coordenador fica no nó de controle com política Retain. Reiniciar o pod
preserva banco/fila. Perder esse nó ou excluir o cluster não é tolerado por esta solução.
O teste de drain atua sobre um worker, explicitamente registrado nas evidências.
PDB minAvailable=1 exige duas ou mais réplicas para uma drenagem útil sem bloqueio.

## Preparação

```powershell
./scripts/preparar_kubernetes.ps1
python -m unittest carga.test_loadgen -v
python scripts/validar_kubernetes.py
python scripts/experimentos.py --etapa tudo
# Dependência apenas para gerar os gráficos, não para o gerador de carga:
python -m pip install -r carga/requirements-graficos.txt
# Use a pasta impressa pelo comando anterior:
python scripts/relatorio_experimentos.py resultados/execucao-AAAAMMDD-HHMMSS
```

O bootstrap não apaga imagens, contêineres, volumes nem bancos anteriores. Baixa kind
0.33.0 quando necessário e constrói as imagens com os testes Maven. A imagem 3.0.1
altera os metadados da versão para demonstrar coexistência e rollback.

O metrics-server 0.8.0 foi obtido do repositório oficial, preservado em metrics-server.yaml.
O patch de certificados inseguros é exclusivo dos kubelets do laboratório local.
Referências: https://kind.sigs.k8s.io/docs/user/quick-start/,
https://github.com/kubernetes-sigs/metrics-server/releases/tag/v0.8.0,
https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale-walkthrough/.

## Rotas e controle

As rotas originais continuam disponíveis, inclusive SSE. `GET /<dominio>/metricas` informa
fila_pendente, grupos_abertos e modo de escrita. É uma rota de diagnóstico do laboratório.
`APS_COORDENADOR` ativa atendimento sem estado; quando ausente, mantém a API original.
`POD_NAME` vem da Downward API. `APS_VERSAO` identifica a versão. `APS_ESCRITA=sincrona`
aguarda o commit antes da resposta para leituras completas de manancial/inversão;
o padrão é assincrona. O experimento comparativo usa manancial. Alagamento conserva
seu agrupamento assíncrono, mesmo que essa variável seja definida.

Readiness do atendimento consulta a do coordenador. Liveness é local. Rolling updates
usam maxSurge=1, maxUnavailable=0, preStop de 5 segundos e encerramento HTTP gracioso.
HPA usa CPU solicitada de 100m, alvo 60%, mínimo 2, máximo 10; redução estabilizada
em 60 segundos no laboratório (não o padrão de cinco minutos do exemplo da ficha).

## Experimentos

1. Base: POST de manancial e GET histórico com limite 20; 1 réplica, sem HPA;
   1/2/4/8/16/32/64 clientes, 20 s por degrau, 5 s descartados, três repetições.
   POST exige validação/fila/escrita; GET exige consulta/contagem/serialização.
   LIMIT estabiliza o tamanho retornado, mas COUNT ainda depende do volume do banco.
2. Manual: mesma carga de 32 clientes, 1/2/4/8 atendimentos; compare ganho com ideal.
3. HPA: 2..10 réplicas, rampa de 120 s até 64 clientes, observação posterior da redução.
4. Falhas: apagar pod de atendimento; drenar worker e restaurar com uncordon.
5. Atualização: 3.0.0→3.0.1, rollout undo, e comparação sem preStop.
6. Assíncrono: mesmos 16 clientes, escrita síncrona versus fila; medir também drenagem.

Hipóteses: escala ajuda somente enquanto atendimento for limitante; HPA reage com atraso;
réplicas sobreviventes mantêm serviço na perda de atendimento; rolling update com readiness
e preStop tende a reduzir erros; fila reduz latência de aceite, mas não cria capacidade de
gravação ilimitada. Registre a hipótese antes do teste; aceite também resultados negativos.

Cada execução grava CSV bruto, série temporal, JSON, comando exato e eventos Kubernetes
com horário UTC. Falhas não são descartadas dos percentis. Os resumos por degrau excluem
aquecimento e incluem a conclusão das requisições iniciadas naquele degrau. O tempo extra
de conclusões aparece em segundos_medidos, evitando ocultar timeouts ao final.
O gerador é de laço fechado: concorrência não é uma taxa fixa de req/s.

O script verifica a fila antes/depois e registra delta de leituras persistidas. Um timeout
pode ocorrer após aceite; não reenvia automaticamente o POST. Divergências exigem análise,
não podem ser chamadas automaticamente de perda. O 202 mede aceite durável, não commit.

Para a ficha, use gráficos e RELATORIO.md gerados a partir dos dados. Interprete CPU do
atendimento, coordenador, ingress, máquina e gerador em conjunto. Uma curva que não chegou
à saturação deve ser registrada assim; não invente capacidade máxima. Os testes não são
uma autorização para apontar o gerador a serviços externos: ele só aceita loopback.
