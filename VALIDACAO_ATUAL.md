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
