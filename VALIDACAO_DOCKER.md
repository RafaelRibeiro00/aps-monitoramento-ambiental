# APS — validação da etapa de conteinerização

Validação realizada em 18/09/2026. Domínio: monitoramento ambiental de mananciais, alagamentos e inversão térmica. Repositório: https://github.com/RafaelRibeiro00/aps-monitoramento-ambiental.

## Checklist da folha

| Nº | Requisito | Resposta | Evidência verificada |
|---|---|---|---|
| 1 | Um Dockerfile por serviço, mínimo 3 | **SIM** | `servicos/manancial/Dockerfile`, `servicos/alagamento/Dockerfile`, `servicos/inversao-termica/Dockerfile`. |
| 2 | Construção multi-stage | **SIM** | Dois `FROM` em cada Dockerfile: Maven/JDK para build e testes; JRE para execução. |
| 3 | Imagem final enxuta, sem SDK e código-fonte | **SIM** | Runtime JRE, JAR e dependências; sem Maven, javac, diretório de build ou arquivos `.java`. Imagens aproximadamente 402 MB descompactadas no Docker Desktop. |
| 4 | Contêiner com usuário não-root | **SIM** | Os três executam como UID/GID 10001, confirmado dentro dos contêineres. |
| 5 | Imagens versionadas por tag | **SIM** | `aps-manancial:1.1.0`, `aps-alagamento:1.1.0`, `aps-inversao-termica:1.1.0`. |
| 6 | .dockerignore exclui build e .git | **SIM** | `.dockerignore` em cada contexto de construção exclui `target/`, `.git/`, arquivos locais e bancos. |
| 7 | docker-compose.yml sobe toda a pilha | **SIM** | Os três serviços foram criados por `docker compose up -d --wait`, todos healthy. SQLite é embutido nas aplicações, com arquivo compartilhado persistente. |
| 8 | Comunicação entre serviços por nome | **SIM** | Rede `monitoramento`; chamadas HTTP reais manancial → alagamento → inversao-termica → manancial pelo DNS do Compose, todas aprovadas. Não há dependência funcional entre APIs na gravação das leituras. |
| 9 | Dados persistem após restart | **SIM** | Bind mount de `APS_DATA_DIR` em `/app/dados`. Nove leituras de teste e dados anteriores preservados após `docker compose restart`; comparação dos dados anteriores por SHA-256. |
| 10 | Ambiente, .env.example versionado e .env ignorado | **SIM** | Modelo `.env.example`, configuração local `.env`, regra no `.gitignore`; portas, diretório, arquivo, versão e configuração das APIs por ambiente. |
| 11 | Sem credenciais no código-fonte | **SIM** | Inspeção do código e configuração sem credenciais encontradas. SQLite local não exige usuário/senha. |
| 12 | Liveness e readiness distintos dentro do contêiner | **SIM** | GET `/health/live` e `/health/ready` testados nos três contêineres. Testes Java simulam tabela indisponível, bloqueio de escrita e recuperação: live 200, ready 503 durante a falha e 200 após recuperar. |
| 13 | Cliente leve consumindo a pilha | **SIM** | Três geradores Python sem bibliotecas externas enviaram três leituras cada; nove HTTP 200 e nove registros confirmados no SQLite. |

## Resultado

- **123 testes Java aprovados**, 41 por serviço, executados durante o build; nenhuma falha ou erro.
- Três contêineres `cont-manancial`, `cont-alagamento` e `cont-inversao-termica` em execução e saudáveis após o restart.
- Só as três imagens atuais `1.1.0` foram mantidas; imagens antigas removidas após validar a substituição.
- Banco ativo: `C:\Users\Rafae\APS-Docker\dados\aps.db`.
- Backup anterior à migração: `C:\Users\Rafae\APS-Docker\backups-20260918\antes-compose-203610.db`.
- Evidência automática: `validacao-docker.json`. Roteiro reproduzível: `python scripts/validar_docker.py`.

## Comandos para a apresentação

Execute na raiz do projeto, com o Docker Desktop aberto e o `.env` configurado:

```powershell
docker compose up -d --build --wait
docker compose ps
docker images
docker compose exec manancial id
docker compose exec alagamento id
docker compose exec inversao-termica id
docker compose exec manancial curl -f http://127.0.0.1:8081/health/live
docker compose exec manancial curl -f http://127.0.0.1:8081/health/ready
docker compose exec manancial curl -f http://alagamento:8082/health/ready
python scripts/validar_docker.py
```

No DB Browser, abra o banco ativo, escolha a tabela e atualize pelas setas verdes. A validação adiciona leituras identificadas com `VALIDACAO-...`, sem apagar leituras anteriores. A marcação SIM representa a implementação e os testes locais descritos; a conferência final da folha é feita pelo professor na demonstração. As responsabilidades individuais dos integrantes devem refletir a divisão real do grupo e não foram inventadas.
