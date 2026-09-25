# APS — Monitoramento ambiental

Três APIs Java 17: **manancial (8081)**, **alagamento (8082)** e **inversão térmica (8083)**. SQLite guarda o histórico; SSE transmite leituras prontas sem esperar a gravação. O Python simula os sensores.

## Estado desta entrega

Código atualizado para execução local, com filas persistentes e rotas novas. **Nenhuma imagem Docker foi construída nesta etapa.** Dockerfiles e Compose anteriores permanecem como material da etapa anterior; não representam uma versão Docker validada do fluxo novo. Consulte `VALIDACAO_ATUAL.md` para os resultados e limitações do ambiente.

## Rotas

Use o prefixo do serviço em todas as rotas de aplicação:

| Método | Manancial | Alagamento | Inversão térmica |
|---|---|---|---|
| POST | /manancial/leituras | /alagamento/leituras | /inversao-termica/leituras |
| GET | /manancial/historico | /alagamento/historico | /inversao-termica/historico |
| GET (SSE) | /manancial/tempo-real | /alagamento/tempo-real | /inversao-termica/tempo-real |
| GET | /manancial/alertas | /alagamento/alertas | /inversao-termica/alertas |
| GET | /manancial/alertas-historico | /alagamento/alertas-historico | /inversao-termica/alertas-historico |

Exemplo: `http://localhost:8082/alagamento/alertas`. As rotas antigas sem prefixo retornam 404. Saúde permanece em `GET /health/live` e `GET /health/ready` em cada porta.

## Recebimento assíncrono

`POST /leituras` retorna **202 Accepted**, com `evento_id` e `persistencia: "pendente"`, depois de registrar o trabalho na fila em disco. Isso confirma o recebimento durável, **não a gravação no SQLite**. O SSE pode entregar a leitura antes de ela aparecer no histórico.

A gravação ocorre em segundo plano. Se o SQLite ficar lento ou bloqueado, a fila permanece em disco e tenta novamente. Cada evento tem UUID único para a retomada não duplicar as gravações. As filas ficam ao lado do banco, em `aps.db.fila-manancial`, `aps.db.fila-alagamento` e `aps.db.fila-inversao-termica`. Não apague essas pastas enquanto houver dados pendentes.

Há limite de 10 mil trabalhos/pontos pendentes por serviço (ajustável por `-Daps.fila.limite`), até 1.000 pontos de alagamento aguardando agrupamento, 16 clientes SSE simultâneos e 64 eventos na fila de cada cliente. A API retorna 503 em sobrecarga e o cliente lento não bloqueia o produtor ou as outras conexões. O SSE é transmissão ao vivo: reconecte e consulte o histórico para recuperar lacunas. Não há replay automático por `Last-Event-ID` nesta versão.

O armazenamento da fila também precisa funcionar: uma falha de disco impede confirmar o POST. Não existe promessa de entrega sem um registro durável. A fila usa gravação forçada e substituição do arquivo; uma queda abrupta da máquina continua dependendo das garantias do sistema de arquivos.

## Manancial e inversão térmica

Continuam recebendo três medições juntas, sem janela de agrupamento.

Manancial:

```json
{"area":"AREA-001","timestamp":"2026-09-24T15:30:00Z","percentual_ocupado":35.5,"nivel_agua_m":12.3,"temperatura_agua_c":22.5}
```

Inversão térmica:

```json
{"estacao":"ESTACAO-001","timestamp":"2026-09-24T15:30:00Z","umidade":45,"temperatura_c":25,"vento_km_h":8}
```

## Sensores individuais de alagamento

Cada sensor envia seu próprio POST para `/alagamento/leituras`:

```json
{"sensor_id":"NIVEL-001","ponto_id":"PONTO-001","tipo":"nivel_corrego","valor":210,"unidade":"cm","timestamp":"2026-09-24T15:30:00Z"}
```

| Tipo | Unidade obrigatória | Campo consolidado |
|---|---|---|
| nivel_corrego | cm | nivel_corrego_cm |
| chuva | mm | chuva_mm |
| velocidade_agua | m/s | velocidade_agua_m_s |

Os seis campos são obrigatórios; `valor` precisa ser numérico, finito e não negativo. Os sensores do mesmo local usam o mesmo `ponto_id`.

A primeira medição abre uma janela de **3 segundos de espera pelo relógio da API**. As três medições também precisam ter horários separados por no máximo 3 segundos entre a mais antiga e a mais recente. Se todas chegarem antes, o conjunto fecha imediatamente. Senão, fecha ao vencer o prazo com `null` nos campos ausentes e `status: "incompleta"`. Uma nova medição depois do fechamento abre outro conjunto; não altera o anterior.

Um segundo envio do mesmo tipo enquanto o conjunto está aberto retorna **409**, assim como um timestamp fora da janela atual. Não há substituição silenciosa da primeira medição. Sensores de pontos diferentes nunca são misturados. Use relógios sincronizados; os 3 segundos não representam uma agregação meteorológica oficial de chuva.

Exemplo recebido no SSE:

```json
{"sensor":"PONTO-001","ponto_id":"PONTO-001","timestamp":"2026-09-24T15:30:02Z","nivel_corrego_cm":210,"chuva_mm":30,"velocidade_agua_m_s":null,"status":"incompleta","sensores_ausentes":["velocidade_agua"],"evento_id":"..."}
```

O timestamp único do conjunto é o horário da medição mais recente, normalizado para UTC. As medições individuais preservam seus horários originais no banco. O campo legado `sensor` representa o ponto na tabela consolidada; `ponto_id` é a identificação explícita nova.

## Consultar histórico

```text
http://localhost:8081/manancial/historico?area=AREA-001&nivel_agua_m_min=10
http://localhost:8082/alagamento/historico?ponto_id=PONTO-001&data=2026-09-24
http://localhost:8083/inversao-termica/historico?estacao=ESTACAO-001&temperatura_c_min=30
```

Filtros opcionais combinados com E:

- Identificação: `sensor` como alias comum, ou `area`, `ponto_id` e `estacao` conforme o serviço.
- `data=AAAA-MM-DD`, considerando UTC.
- `data_inicio` e `data_fim`: RFC 3339 com fuso, limites inclusivos. Codifique `+` como `%2B`. A comparação do SQLite usa precisão de milissegundos.
- Cada medição aceita igualdade (`chuva_mm=30`) ou intervalo (`chuva_mm_min=10&chuva_mm_max=50`).
- `limite` de 1 a 1000 (padrão 100), `offset` a partir de 0 e `ordem=asc|desc`.

Resposta: `{"total":250,"limite":100,"offset":0,"leituras":[...]}`. Percorra as páginas para consultar tudo. Leituras são ordenadas por ID de gravação. Nulos não atendem a filtros numéricos. Filtros inválidos retornam 400; valores são parametrizados no SQL.

## Alertas recentes e históricos

`GET /<servico>/alertas` lê **somente a memória**, sem consultar o banco. Retorna `{"alertas":[...]}` com cada alerta gerado nos últimos 60 segundos. Cada alerta expira individualmente a partir de `criado_em`; consultar não renova o prazo. Lista vazia significa ausência de alertas recentes, não uma avaliação de que tudo está normal.

Até 10 mil alertas são mantidos em memória; em excesso, os mais antigos saem antes para proteger a API. Reiniciar limpa a memória. O frontend deve usar `evento_id` para evitar mostrar repetidamente o mesmo alerta e pode consultar esta rota a cada 5 segundos.

`GET /<servico>/alertas-historico` consulta o SQLite, com os mesmos filtros do histórico e `status_notificacao=pendente|enviado`. Para o último por horário de geração:

```text
http://localhost:8082/alagamento/alertas-historico?limite=1&ordem=desc
```

Regras **didáticas**, configuráveis no `.env`:

| Serviço | Condição |
|---|---|
| Manancial | percentual_ocupado ≤ ALERTA_PERCENTUAL_BAIXO (20%) |
| Alagamento | nivel_corrego_cm ≥ ALERTA_NIVEL_CORREGO_CM (200 cm) **ou** chuva_mm ≥ ALERTA_CHUVA_MM (50 mm) |
| Inversão térmica | temperatura_c ≥ ALERTA_TEMPERATURA_C (30 °C), umidade ≤ ALERTA_UMIDADE_MAX (30%) **e** vento_km_h ≤ ALERTA_VENTO_MAX_KM_H (5 km/h) |

No alagamento, nível e chuva podem gerar alertas **assim que a medição individual chega**, sem esperar o conjunto. A consolidação não gera de novo o mesmo alerta. Valores ausentes nunca são tratados como zero. As medições atuais da terceira API não confirmam inversão térmica; o aviso é de calor, ar seco e pouco vento.

O webhook opcional permanece disponível, configurado por `MANANCIAL_WEBHOOK_URL`, `ALAGAMENTO_WEBHOOK_URL` e `INVERSAO_TERMICA_WEBHOOK_URL`. Token Bearer opcional em `*_WEBHOOK_TOKEN`, somente no `.env` local. Sem URL, nenhum envio externo ocorre. O envio usa os alertas já persistidos, retenta falhas e mantém chave `Idempotency-Key`; o destino deve deduplicar repetições. Não há SSE separado para alertas.

## Banco e migração

Banco ativo desta máquina: `C:\Users\Rafae\APS-Docker\dados\aps.db`, definido por `APS_DATA_DIR` e `APS_DB_FILE` no `.env`. `APS_DB` ou `-Daps.db` permitem definir um arquivo explicitamente. Java e Python agora usam o mesmo modelo de configuração.

- `leituras_manancial` e `leituras_inversaotermica`: formato de medições preservado, com UUID de evento adicional.
- `medicoes_alagamento`: medições individuais, horário original e `conjunto_id` para rastreabilidade.
- `leituras_alagamento`: conjuntos, campos numéricos anuláveis, `ponto_id`, `status` e UUID de evento.
- `alertas_*`: histórico de alertas. Os alertas individuais de alagamento podem não ter `leitura_id` consolidado; seus detalhes identificam o sensor e o ponto.

A aplicação migra o esquema ao iniciar. Para migrar antes, com as APIs paradas:

```powershell
python scripts/migrar_banco.py
```

O script cria um backup e preserva os registros existentes. No DB Browser, abra o arquivo ativo, escolha a tabela e atualize pelas setas verdes. A grade não atualiza automaticamente.

`python PYTHON/limpar_banco.py` **apaga todas as leituras e alertas**: pare as APIs e os geradores antes. Se houver eventos pendentes, a limpeza é recusada para impedir que a fila volte a preencher o banco. Deixe a fila terminar antes de limpar. Não execute esse comando apenas para migrar.

## Executar sem Docker

Requisitos: JDK 17 e Maven. No IntelliJ, abra o `pom.xml` da raiz e execute `Api.main` de cada módulo com JDK 17. Ou:

```powershell
python scripts/iniciar_apis.py
```

O script procura Maven no PATH ou no IntelliJ do Windows e usa JAVA_HOME/JDK instalado. `--servico alagamento` inicia só esse projeto. `--somente-compilar` compila sem iniciar. O Windows desta máquina bloqueou a DLL nativa do SQLite por Controle de Aplicativo durante a validação; isso precisa ser resolvido pelo administrador para executar as APIs localmente. Não foi alterada nenhuma política de segurança.

Execute os clientes em outros terminais:

```powershell
python PYTHON/manancial.py --quantidade 3
python PYTHON/inversao_termica.py --quantidade 3
python PYTHON/alagamento.py --quantidade 3
python PYTHON/acompanhar.py alagamento
```

No alagamento, `--quantidade 3` significa três ciclos, com três POSTs individuais por ciclo. Para simular apenas um sensor: `--sensor chuva --intervalo 4`. Assim o conjunto fecha incompleto; não tente repetir o mesmo tipo continuamente dentro da janela aberta. Para gerar tudo normalmente, use o padrão `--sensor todos`.

Console da API: `Medicao recebida`, `Conjunto completo/incompleto`, `Leitura publicada no SSE`, `Gravacao concluida`, `Alerta gerado`, `Alerta enviado ao webhook` ou `Alerta pendente`. O Python mostra aceites HTTP 202; não os chama de gravações confirmadas.

## SSE no frontend

```javascript
const conexao = new EventSource("http://localhost:8082/alagamento/tempo-real");
conexao.addEventListener("leitura", evento => {
  const leitura = JSON.parse(evento.data);
  console.log(leitura);
});
// conexao.close();
```

Para outra origem de frontend, configure `APS_CORS_ORIGINS=http://localhost:3000` (mais de uma separada por vírgula). Não existe autenticação nesta etapa; CORS não substitui autenticação. As APIs locais escutam em 127.0.0.1 por padrão. Cada conexão recebe comentários de manutenção a cada 10 segundos e eventos conforme as leituras ficam prontas.

## Validação

```powershell
mvn -B -ntp verify
python -m unittest discover -s scripts -p "test_*.py"
```

O GitHub Actions executa esses testes em Linux com Java 17, sem construir imagens Docker. Cobertura: validações, migração, filtros, banco bloqueado sem travar SSE, fila após restart, gravação idempotente, agrupamento completo/incompleto, expiração de alertas e webhook com falha/recuperação. A capacidade máxima de requisições ainda não foi medida; limites de fila não são uma promessa de desempenho.
