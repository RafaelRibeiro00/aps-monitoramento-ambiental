# Validação das etapas 4 e 5

Execução concluída em 5 de novembro de 2026 no cluster local `kind-aps-experimentos`.
Os três nós virtuais compartilham a mesma máquina física, com processador Intel Core
i3-1215U, 6 núcleos, 8 processadores lógicos e aproximadamente 8 GB de RAM. O gerador
rodou no Windows da mesma máquina. A entrada medida foi o Ingress em
`http://127.0.0.1:8088`.

## Estado validado

- 114 testes Java e 3 testes do gerador Python aprovados.
- Integração das três APIs aprovada: POST, histórico, alertas, SSE, distribuição entre
  duas réplicas e persistência após reinício do pod de estado.
- 18 execuções de carga com 132 arquivos de evidência antes da compactação.
- Linha de base de POST e GET executada três vezes com 1, 2, 4, 8, 16, 32 e 64 clientes.
- Escala manual com 1, 2, 4 e 8 réplicas.
- HPA por CPU com mínimo 2 e máximo 10, carga em rampa e redução observada.
- Exclusão de pod e drenagem/retorno de um nó worker sob carga.
- Rolling update 3.0.0 para 3.0.1, coexistência comprovada por `X-Versao` e rollback.
- Escrita síncrona comparada com a fila assíncrona usando a mesma carga.

## Resultado principal da linha de base

A maior vazão média do POST foi 99,91 respostas 202/s com 16 clientes. Com 32 clientes,
a vazão caiu para 84,42/s e o p95 médio subiu para 886,61 ms. Com 64 clientes, foram
80,05/s e p95 médio de 1.549,88 ms. O ponto de saturação observado fica entre 16 e 32
clientes nesta máquina. A fila acumulou trabalho após a carga, então a capacidade de
aceite 202 não deve ser confundida com a capacidade sustentada de commit no SQLite.

O GET paginado sustentou em média 214,16 respostas/s com 64 clientes, p95 médio de
445,51 ms e zero erro nas três repetições.

## Escalabilidade e resiliência

Com 32 clientes, a escala manual produziu 84,66/s com uma réplica; 90,84/s com duas;
92,31/s com quatro; e 84,22/s com oito. O ganho máximo observado foi 1,09 vez, distante
do ideal. Coordenador, fila, SQLite, Ingress e a máquina permanecem compartilhados.

O HPA chegou a 10 réplicas e voltou a 2. Na rampa extrema, registrou 10,47% de erros e
p95 de 5.012,32 ms. A consulta da fila pelo Ingress não se recuperou dentro do limite de
180 segundos do roteiro; consulta direta posterior confirmou fila vazia. Esse resultado
é evidência de saturação do caminho compartilhado, não falha do controlador HPA.

Excluir um pod produziu zero erros em 4.825 requisições. Drenar um worker produziu um
erro em 7.007 requisições. O nó que contém o volume local do SQLite não foi removido;
a arquitetura atual não promete sobreviver à perda desse nó.

## Atualização e processamento assíncrono

O rolling update registrou 1,10% de erros, o rollback 0,34% e o cenário sem `preStop`
1,67%. As versões 3.0.0 e 3.0.1 foram observadas durante as trocas. Uma execução de cada
cenário mostra o comportamento observado, mas não estabelece causalidade estatística.

A escrita síncrona atingiu 40,99 respostas/s, p95 de 829,52 ms e zero erro. A assíncrona
atingiu 60,81 respostas/s, p95 de 524,62 ms e zero erro. Após a drenagem, o aumento de
linhas persistidas coincidiu com os aceites nas duas execuções.

## Evidências

O relatório completo, tabelas, comandos, gráficos e referências aos arquivos ficam em
`resultados/execucao-20261105-completa/RELATORIO.md`. Cada cenário possui resumo JSON e
dois gráficos. Os CSVs por requisição, séries por segundo, eventos Kubernetes, consoles e
descrição do ambiente são preservados no arquivo compactado de dados brutos. Os arquivos
locais não compactados continuam disponíveis no workspace e são ignorados pelo Git para
evitar duplicação.
