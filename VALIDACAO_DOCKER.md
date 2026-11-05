# Validacao Docker 2.0.0 — 25/09/2026

## Resultado

Tres imagens e tres containers definitivos, todos healthy:

| Imagem | Container | Porta publicada |
|---|---|---|
| aps-manancial:2.0.0 | cont-manancial | 9081 |
| aps-alagamento:2.0.0 | cont-alagamento | 9082 |
| aps-inversao-termica:2.0.0 | cont-inversao-termica | 9083 |

Os containers/imagens antigos foram removidos anteriormente; os containers temporarios desta validacao tambem foram removidos. Permanecem somente as tres imagens 2.0.0 e os tres containers acima.

## Verificacoes aprovadas

- Build multi-stage dos tres Dockerfiles, com 109 testes Java aprovados (48 + 13 + 48), zero falhas/erros.
- Imagem final com JRE, JAR e dependencias; usuario 10001:10001 confirmado por docker exec id.
- Integracao isolada nas imagens reais: 16 POSTs aceitos, 10 leituras consolidadas e 10 medicoes individuais de alagamento no SQLite.
- Todas as rotas de aplicacao, filtros de historico, paginacao, alertas recentes e persistidos; health/live e health/ready.
- Eventos SSE comparados com registros SQLite por evento_id e valor.
- Alagamento completo com timestamp mais recente; incompleto apos 3 segundos com null no banco e SSE; duplicata rejeitada.
- Requisicoes invalidas e metodos incorretos rejeitados.
- Reinicio dos tres containers temporarios preservou todas as leituras.
- Compose definitivo subiu com --wait, todos healthy.
- Python real enviou mais 5 POSTs aos containers definitivos; 3 leituras consolidadas de demonstracao ficaram no banco principal e foram recebidas por SSE.
- Comunicacao por nome na rede Compose confirmada entre os tres servicos.

## Persistencia

Banco principal preservado: C:/Users/Rafae/APS-Docker/dados/aps.db.
Backup anterior: C:/Users/Rafae/APS-Docker/dados/backups/antes-docker-2-20260925-111928.db.
Bind mount em /app/dados preserva banco e filas persistentes. Nenhum volume ou banco foi apagado.

## Reproduzir

```powershell
docker compose build
docker compose up -d --wait
python scripts/validar_docker.py
```

O teste isolado assume tag 2.0.0 (sobrescrevivel por APS_TEST_IMAGE_TAG); DOCKER_CLI permite indicar o executavel caso nao esteja no PATH. Nao houve teste de carga nem envio de webhook externo real. A autenticacao nao faz parte desta etapa; portas publicadas somente em 127.0.0.1.
