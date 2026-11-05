# Validacao da entrega Java/Python — 25/09/2026

## Implementado

- Prefixos dos tres servicos e cinco rotas de aplicacao: leituras, historico, tempo-real, alertas e alertas-historico.
- Recebimento HTTP 202 com fila persistente antes do aceite, gravacao assincrona e SSE independente do SQLite.
- Alagamento: sensores individuais, agrupamento de ate 3 segundos, nulos quando incompleto e rastreabilidade das medicoes.
- Alertas recentes em memoria com expiracao individual de 60 segundos e historico persistente separado.
- Python, Postman, mensagens do console e configuracao local atualizados.
- Bancos C:/Users/Rafae/APS-Docker/dados/aps.db e dados/aps.db migrados com backups e preservacao dos registros.

## Evidencia

109 testes Java passaram no GitHub Actions em Linux/Java 17 (48 manancial, 13 alagamento, 48 inversao termica). Incluem SQLite bloqueado com SSE ativo, agrupamento, filas apos restart, idempotencia, filtros, alertas e webhook. Dois testes Python passaram localmente, cobrindo geracao de sensores, migracao repetida e limpeza em banco temporario.

Primeira execucao Java: https://github.com/RafaelRibeiro00/aps-monitoramento-ambiental/actions/runs/36089886583 (Java aprovado; etapa Python ainda nao tinha os novos testes publicados nessa revisao). A execucao final e registrada abaixo apos publicar os ajustes.

## Limitacoes locais

A compilacao Java local passou. O Windows bloqueou o carregamento de sqlitejdbc.dll por nao confirmar o publicador; a captura enviada pelo usuario confirma esse bloqueio. Por isso, execucao integrada local das APIs ainda nao esta aprovada. Nenhuma politica de seguranca foi alterada. E necessaria a liberacao legitima da dependencia pelo administrador ou um ambiente aprovado para executar a aplicacao.

Por orientacao final do usuario, Docker ficou fora desta entrega: nenhuma imagem foi gerada. Tentativas anteriores de inicializacao para limpeza encontraram erro em sockets temporarios; nao foi concluida a remocao dos containers/imagens. Nao foi feito reset de fabrica nem apagado volume/banco. A pasta temporaria de sockets Docker/run foi preservada como Docker/run.backup-20260925 durante o diagnostico, antes da orientacao de interromper.

Ainda nao foi medida a capacidade maxima de requisicoes por segundo. As filas e limites implementados nao equivalem a um teste de carga.

## Validacao final

Execucao: https://github.com/RafaelRibeiro00/aps-monitoramento-ambiental/actions/runs/36142798190

Aprovados: 109 testes Java, 2 testes Python e integracao com tres processos Java reais, 10 POSTs dos simuladores, 6 leituras consolidadas no SQLite e eventos SSE nas tres APIs. Nenhum Docker foi usado. A compilacao final tambem passou no Windows; a execucao local continua bloqueada pela politica de carregamento da DLL SQLite.

A publicacao foi feita por um checkout temporario porque a pasta do Google Drive apresentou travas Git antigas. Os arquivos locais foram preservados; nenhuma exclusao das imagens pessoais ja ausentes foi incluida nos commits.

## Reteste local apos reinicializacao — 25/09/2026

O bloqueio da DLL SQLite nao ocorreu no reteste. Passaram no Windows os 109 testes Java, os 2 testes Python e a integracao completa (10 POSTs, 6 leituras consolidadas, SSE nas tres APIs), usando banco temporario. Esta verificacao substitui a limitacao local registrada acima.

As portas 8081–8083 estavam ocupadas pelo processo do Docker. Sem alterar Docker, o .env local foi ajustado para 9081 (manancial), 9082 (alagamento) e 9083 (inversao-termica). Java e Python leem essa configuracao. As tres APIs foram iniciadas diretamente em Java, com readiness e consulta ao historico aprovados, usando C:/Users/Rafae/APS-Docker/dados/aps.db. Nao e necessario inicia-las de novo enquanto esses processos estiverem ativos.

## Validacao completa das rotas — 25/09/2026, 11h16

Aprovados localmente: 109 testes Java (zero falhas/erros), 2 testes Python e integracao HTTP ampliada em scripts/validar_local.py. Foram aceitos 16 POSTs, persistidas 10 leituras consolidadas e 10 medicoes individuais de alagamento no banco temporario.

Nas tres APIs foram verificados POST /leituras; GET /historico (identificador, data, valor minimo, paginacao e ordem); GET /tempo-real com igualdade de evento_id e valor entre SSE e SQLite; GET /alertas com leitura critica; GET /alertas-historico filtrado; GET /health/live e /health/ready. Tambem passaram corpo invalido (400), filtro invalido (400), metodo incorreto (405), rota inexistente (404) e sensor duplicado (409).

No alagamento, o conjunto completo usa o timestamp mais recente; o conjunto incompleto foi publicado apos aproximadamente 3 segundos, com chuva e velocidade null tanto no SSE quanto no historico. A suite Java tambem aprovou expiracao individual de alertas em 60 segundos com relogio controlado, SSE durante bloqueio de escrita do SQLite, recuperacao da fila e persistencia idempotente.

Os processos de integracao foram encerrados ao final. Nenhuma API de teste foi deixada rodando em segundo plano. Banco principal preservado; Docker nao utilizado nesta validacao. Os tres containers/imagens antigos haviam sido removidos a pedido do usuario antes desta rodada. Nao houve teste de carga nem envio para destino externo real de notificacoes.

## Entrega Docker posterior

A pedido do usuario, foram construidas e validadas as tres imagens 2.0.0 e iniciados os containers definitivos. O resultado atual esta em VALIDACAO_DOCKER.md e substitui as restricoes historicas sobre nao executar Docker. As APIs agora permanecem rodando nos containers; nao iniciar copias locais sobre a mesma fila.
