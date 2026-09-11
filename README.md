# APS — Monitoramento ambiental com Java, SQLite e Python

O projeto recebe e armazena leituras de três temas ambientais: **manancial**, **alagamento** e **inversão térmica**. Cada tema possui uma API Java independente e um gerador Python que simula um sensor. Também é possível enviar leituras manualmente pelo Postman.

Cada requisição contém **um identificador, um timestamp e três medições**. As APIs validam esses cinco campos e salvam os dados em um banco SQLite compartilhado. O sistema mantém o histórico das leituras; não calcula alertas, previsões ou diagnósticos ambientais automaticamente.

## 1. Como o projeto funciona

```text
Postman ou gerador Python
         |
         | POST /leituras com um objeto JSON
         v
API Java do tema escolhido
         |
         | Valida identificador, data/hora e três medições
         v
Tabela do tema no arquivo dados/aps.db
         |
         | Gravação concluída
         v
Resposta HTTP 200 e mensagem "Leitura recebida com sucesso."
```

Dados inválidos recebem HTTP 400 e não são gravados. Se a gravação falhar, a API retorna HTTP 500. Parar uma API não apaga os dados salvos; cada API pode funcionar sem que as outras estejam iniciadas.

### Endereços e portas

| API | Configuração no IntelliJ | Porta | Endereço da requisição | Gerador Python |
|---|---|---:|---|---|
| Manancial | API - Manancial | 8081 | `http://127.0.0.1:8081/leituras` | `manancial.py` |
| Alagamento | API - Alagamento | 8082 | `http://127.0.0.1:8082/leituras` | `alagamento.py` |
| Inversão térmica | API - Inversao Termica | 8083 | `http://127.0.0.1:8083/leituras` | `inversao_termica.py` |

As três rotas usam **POST**, corpo **JSON** e cabeçalho `Content-Type: application/json`. Não exigem autenticação. O endereço `127.0.0.1` representa o próprio computador: os clientes devem executar na mesma máquina que as APIs. As APIs estão configuradas para escutar apenas nesse endereço local.

## 2. Regras gerais das requisições

Todos os cinco campos de cada tema são obrigatórios. Use os nomes exatamente como aparecem nos exemplos, respeitando letras minúsculas e sublinhados.

| Tipo de dado | Como enviar | Regra |
|---|---|---|
| Identificador | Texto entre aspas, como `"SENSOR-001"` | Não pode estar vazio, conter apenas espaços ou ser `null`. |
| `timestamp` | Texto como `"2026-09-11T15:30:00-03:00"` | Data/hora válida em RFC 3339, incluindo segundos e fuso. |
| Medições | Números, como `120` ou `15.2` | Sem aspas, finitos e dentro dos limites do campo. |

O `timestamp` é o instante da medição informado pelo sensor. `-03:00` indica o deslocamento em relação a UTC; `Z` indica UTC. Frações de segundo também são aceitas. A API verifica a data e o formato, mas não exige que o horário seja o atual.

Use **ponto** nos decimais: `15.2`, não `15,2`. O número zero é aceito; campo ausente ou `null` não equivale a zero. Texto numérico, como `"15.2"`, é rejeitado. Campos extras, listas no lugar do objeto, comentários e JSON malformado também são rejeitados. O corpo da requisição pode ter até **4096 bytes**.

Os identificadores não precisam de cadastro prévio. Repetir uma requisição válida gera outro registro: não há eliminação automática de duplicatas.

## 3. API de manancial — porta 8081

Recebe leituras associadas a uma área de manancial. O campo `area` identifica a origem da leitura; as três medições informam o percentual ocupado, o nível e a temperatura da água.

**Destino:** `POST http://127.0.0.1:8081/leituras`  
**Tabela:** `leituras_manancial`

| Campo | Tipo JSON | Unidade | Significado e validação |
|---|---|---|---|
| `area` | Texto | — | Identificador da área, não vazio. |
| `timestamp` | Texto | Data/hora | Momento da leitura, com segundos e fuso. |
| `percentual_ocupado` | Número | % | Percentual ocupado informado para a área; de 0 a 100. |
| `nivel_agua_m` | Número | m | Nível da água informado pelo sensor; maior ou igual a 0. |
| `temperatura_agua_c` | Número | °C | Temperatura da água; maior ou igual a -273.15. |

```json
{
  "area": "AREA-001",
  "timestamp": "2026-09-11T15:30:00-03:00",
  "percentual_ocupado": 35.5,
  "nivel_agua_m": 12.3,
  "temperatura_agua_c": 22.5
}
```

A API valida os valores e insere uma linha na tabela de manancial. O percentual é recebido diretamente; não é calculado a partir do nível da água.

## 4. API de alagamento — porta 8082

Recebe leituras de um sensor que informa nível do córrego, quantidade de chuva e velocidade da água. O campo `sensor` permite identificar qual equipamento ou simulador enviou cada leitura.

**Destino:** `POST http://127.0.0.1:8082/leituras`  
**Tabela:** `leituras_alagamento`

| Campo | Tipo JSON | Unidade | Significado e validação |
|---|---|---|---|
| `sensor` | Texto | — | Identificador do sensor, não vazio. |
| `timestamp` | Texto | Data/hora | Momento da leitura, com segundos e fuso. |
| `nivel_corrego_cm` | Número | cm | Nível do córrego; maior ou igual a 0. |
| `chuva_mm` | Número | mm | Chuva acumulada informada pelo sensor; maior ou igual a 0. |
| `velocidade_agua_m_s` | Número | m/s | Velocidade da água; maior ou igual a 0. |

```json
{
  "sensor": "SENSOR-001",
  "timestamp": "2026-09-11T15:30:00-03:00",
  "nivel_corrego_cm": 120,
  "chuva_mm": 15.2,
  "velocidade_agua_m_s": 1.5
}
```

A API grava as três medições juntas na tabela de alagamento. `chuva_mm` representa o acumulado para o intervalo adotado pelo sensor; a requisição não especifica a duração desse intervalo e a API não calcula taxa de chuva. Os valores são armazenados sem classificar o risco de alagamento.

## 5. API de inversão térmica — porta 8083

Recebe dados meteorológicos de uma estação: umidade, temperatura e velocidade do vento. O campo `estacao` identifica a origem da leitura.

**Destino:** `POST http://127.0.0.1:8083/leituras`  
**Tabela:** `leituras_inversaotermica`

| Campo | Tipo JSON | Unidade | Significado e validação |
|---|---|---|---|
| `estacao` | Texto | — | Identificador da estação, não vazio. |
| `timestamp` | Texto | Data/hora | Momento da leitura, com segundos e fuso. |
| `umidade` | Número | % | Umidade relativa do ar; de 0 a 100. |
| `temperatura_c` | Número | °C | Temperatura do ar; maior ou igual a -273.15. |
| `vento_km_h` | Número | km/h | Velocidade do vento; maior ou igual a 0. |

```json
{
  "estacao": "ESTACAO-001",
  "timestamp": "2026-09-11T15:30:00-03:00",
  "umidade": 68.5,
  "temperatura_c": 24.5,
  "vento_km_h": 8.2
}
```

A API valida os valores e salva uma linha na tabela de inversão térmica. Nesta implementação, ela apenas coleta e armazena essas medições; não determina se está ocorrendo inversão térmica.

## 6. Respostas e problemas comuns

| Resultado | Significado | O que conferir |
|---|---|---|
| `200 OK` | Leitura validada e salva no SQLite. | O console mostra `Leitura salva:` seguido dos dados. |
| `400 Bad Request` | JSON ou campo inválido; leitura não gravada. | Leia o campo `erro` da resposta e confira os cinco campos. |
| `404 Not Found` | Caminho incorreto. | Use exatamente `/leituras`. |
| `405 Method Not Allowed` | Método incorreto. | Use POST. Abrir a URL no navegador normalmente envia GET. |
| `500 Internal Server Error` | Falha ao salvar no banco. | Confira o console Java, o caminho e eventuais bloqueios de escrita. |
| Conexão recusada | Não foi possível conectar à API. | Inicie a API e confira a porta e o endereço do cliente. |

Resposta de sucesso:

```json
{"mensagem":"Leitura recebida com sucesso."}
```

Exemplo de erro ao enviar `umidade` igual a `101`:

```json
{"erro":"umidade deve ser um número entre 0 e 100, inclusive."}
```

Se aparecer erro de porta ocupada ao iniciar a API, confira se uma execução anterior ainda está aberta. Depois de alterar código, porta ou configuração, pare e execute novamente a API correspondente.

## 7. Organização do código e variáveis principais

```text
APS/
├── pom.xml                         Projeto Maven com os três módulos
├── README.md                       Este guia
├── APS.postman_collection.json      Requisições prontas para importar
├── .run/                           Configurações de execução do IntelliJ
├── dados/
│   └── aps.db                      Banco compartilhado
├── PYTHON/
│   ├── manancial.py
│   ├── alagamento.py
│   ├── inversao_termica.py
│   └── limpar_banco.py
└── servicos/
    ├── manancial/
    ├── alagamento/
    └── inversao-termica/
```

Cada módulo possui seu `pom.xml`, o código em `src/main/java` e os testes em `src/test/java`.

| Arquivo Java | Responsabilidade |
|---|---|
| `Api.java` | Inicia o servidor HTTP, recebe POST, valida a rota e o corpo, solicita a gravação e responde ao cliente. |
| `Leitura.java` | Representa os cinco campos de uma leitura, interpreta o JSON e valida os tipos e limites. |
| `Banco.java` | Localiza o SQLite, cria ou atualiza a tabela e grava as leituras com SQL parametrizado. |

| Variável/configuração Java | Para que serve |
|---|---|
| `PORTA`, em `Api.java` | Define a porta do módulo: 8081, 8082 ou 8083. Se mudar, atualize também o Postman e `URL_API` no gerador. |
| `JSON` | Instância do Gson utilizada para converter dados entre JSON e Java. |
| `CAMPOS`, em `Leitura.java` | Conjunto dos cinco nomes aceitos no JSON; campos desconhecidos são rejeitados. |
| Propriedade `aps.db` | Permite indicar outro arquivo SQLite nas opções da JVM. Sem ela, usa `dados/aps.db` na raiz do projeto. |
| `busy_timeout`, em `Banco.java` | Aguarda até 5000 ms por um bloqueio de escrita antes de falhar. |

O projeto usa Java com `release 17`. As dependências dos módulos incluem Gson para JSON, SQLite JDBC para banco e JUnit para testes; o Maven gerencia o download.

## 8. Banco de dados: finalidade e estrutura

O SQLite mantém o histórico das leituras em um arquivo, mesmo depois de encerrar os programas. Não é necessário iniciar um servidor de banco nem configurar usuário e senha.

O banco padrão está em `dados/aps.db`, na raiz da APS. Nesta máquina:

```text
G:\Meu Drive\PESSOAL\FACULDADE\8 semestre\APS\dados\aps.db
```

As três APIs usam esse mesmo arquivo, cada uma com sua tabela. A raiz do projeto é localizada a partir do diretório de execução, subindo pelas pastas até encontrar `pom.xml` e `servicos`. As configurações compartilhadas do IntelliJ usam a raiz como diretório de trabalho. O console informa o caminho efetivamente utilizado.

### Tabelas e colunas

| Tabela | Identificador (`TEXT`) | Medições (`REAL`) |
|---|---|---|
| `leituras_manancial` | `area` | `percentual_ocupado`, `nivel_agua_m`, `temperatura_agua_c` |
| `leituras_alagamento` | `sensor` | `nivel_corrego_cm`, `chuva_mm`, `velocidade_agua_m_s` |
| `leituras_inversaotermica` | `estacao` | `umidade`, `temperatura_c`, `vento_km_h` |

Todas as tabelas também possuem:

| Coluna | Tipo SQLite | Finalidade |
|---|---|---|
| `id` | `INTEGER PRIMARY KEY AUTOINCREMENT` | Identificador automático da linha, com sequência própria por tabela. |
| `timestamp` | `TEXT` | Data/hora enviada pelo sensor, preservada com seu fuso. |
| `recebido_em` | `TEXT` | Data/hora UTC gerada no momento da inserção no banco. |

Cada linha contém **sete colunas**: ID, identificador da origem, duas datas/horas e três medições. `timestamp` e `recebido_em` podem ser diferentes: uma leitura antiga pode ser enviada hoje. Não existe uma tabela separada de cadastro de sensores nem relacionamento entre as três tabelas.

Na inicialização, cada API cria sua tabela se necessário e adiciona as colunas novas que ainda estiverem ausentes. Leituras da versão antiga permanecem com `NULL` nas duas medições que não eram coletadas. As novas requisições exigem todas as medições. Reiniciar a API preserva os registros existentes.

Cada gravação abre e fecha sua conexão. As instruções INSERT usam parâmetros para os valores enviados. O banco fica na pasta sincronizada do Google Drive; evite usá-lo simultaneamente em computadores diferentes.

Para mudar o caminho nas APIs, use a mesma opção de JVM nas três execuções:

```text
-Daps.db=C:/APS/dados/aps.db
```

Essa opção afeta somente as APIs Java. O script de limpeza usa a variável `BANCO`; ajuste-a também se decidir trabalhar com outro arquivo.

### Como consultar os dados

Em um visualizador SQLite, como o DB Browser for SQLite, abra o arquivo `dados/aps.db`. Na aba de navegação dos dados, selecione a tabela desejada e atualize a visualização após novos envios. Também é possível executar:

```sql
SELECT * FROM leituras_manancial ORDER BY id DESC;
SELECT * FROM leituras_alagamento ORDER BY id DESC;
SELECT * FROM leituras_inversaotermica ORDER BY id DESC;
```

Para consultar somente um sensor ou contar registros:

```sql
SELECT * FROM leituras_alagamento
WHERE sensor = 'SENSOR-001'
ORDER BY id DESC;

SELECT COUNT(*) AS total FROM leituras_alagamento;
```

`ORDER BY id DESC` mostra as inserções mais recentes primeiro. A API disponibiliza a rota de envio; não há rota GET para consultar o histórico.

## 9. Testes do projeto

No IntelliJ, execute **Lifecycle → test** na janela Maven para testar os módulos. Se o Maven estiver disponível no terminal, execute `mvn test` na raiz do projeto.

| Testes | O que verificam |
|---|---|
| `ApiTest` | Rotas, métodos, formato JSON, identificadores, datas e validações. |
| `PersistenciaTest` | Gravação por HTTP, dados preservados após reiniciar e resposta 500 em falha de gravação. |
| `MedicoesTest` | Novas medições obrigatórias, limites e migração de bancos antigos sem perda de registros. |

Última verificação da implementação: **120 testes Java aprovados, 40 por módulo**. Os exemplos da coleção Postman também foram enviados por HTTP a APIs temporárias com banco compartilhado.

Os geradores Python foram verificados com 24 envios aceitos, nas taxas de 1 e 2 requisições/s. A limpeza foi testada em banco temporário, incluindo preservação das tabelas, repetição da limpeza e reversão em caso de erro. Os testes não limpam o banco real. Esses resultados se referem à implementação verificada, não significam que as APIs estejam em execução agora.

## 10. Tutorial: iniciar as APIs e enviar pelo Postman

1. Abra o `pom.xml` da raiz da APS como projeto Maven no IntelliJ.
2. Configure um **JDK 17 ou superior** e aguarde a sincronização do Maven. Java 8 não é suficiente.
3. Execute as configurações **API - Manancial**, **API - Alagamento** e **API - Inversao Termica**, ou apenas a API que deseja usar.
4. Confira a porta e o caminho do banco no console. Mantenha a execução aberta.
5. No Postman, importe `APS.postman_collection.json` e envie uma das requisições. Reimporte a coleção se estiver usando uma cópia antiga com menos campos.
6. Confira o HTTP 200 e consulte a tabela correspondente no SQLite.

Para montar uma requisição manualmente, escolha POST, informe o endereço, selecione **Body → raw → JSON**, use **No Auth** e copie o exemplo do tema. Use o Postman desktop para acessar as APIs locais; no Postman web, será necessário um agente local compatível.

## 11. Tutorial: executar os scripts Python

Os quatro scripts estão em `PYTHON` e usam somente a biblioteca padrão do Python 3. Não é necessário instalar pacotes com `pip`.

### Passo 1 — Conferir o Python e entrar na pasta

No PowerShell, verifique se o Python está disponível:

```powershell
python --version
```

Se o comando não for reconhecido, configure uma instalação do Python 3 no PATH ou use o caminho completo do executável. Se o launcher `py` estiver instalado, você também pode usar `py -3` no lugar de `python` nos comandos abaixo.

Entre na pasta dos scripts:

```powershell
Set-Location -LiteralPath 'G:\Meu Drive\PESSOAL\FACULDADE\8 semestre\APS\PYTHON'
```

### Passo 2 — Ajustar as variáveis do gerador

No topo de cada um dos três geradores há configurações como estas, no exemplo de alagamento:

```python
REQUISICOES_POR_SEGUNDO = 1
URL_API = "http://127.0.0.1:8082/leituras"
IDENTIFICADOR = "SENSOR-001"
TEMPO_LIMITE_SEGUNDOS = 5
```

| Variável | Para que serve |
|---|---|
| `REQUISICOES_POR_SEGUNDO` | Frequência desejada de envios. Deve ser um número finito maior que zero. |
| `URL_API` | Endereço da API correspondente, incluindo porta e `/leituras`. |
| `IDENTIFICADOR` | Origem das leituras. Preenche `area`, `sensor` ou `estacao`, conforme o script. |
| `TEMPO_LIMITE_SEGUNDOS` | Timeout usado na comunicação HTTP; padrão de 5 segundos. |

Exemplos: `1` significa um envio por segundo; `2`, um envio a cada 0,5 segundo; `5`, um envio a cada 0,2 segundo. A frequência é independente em cada gerador. Salve e reinicie o script depois de editar as variáveis.

O primeiro envio é imediato. O identificador permanece fixo, enquanto as três medições são sorteadas novamente a cada envio e o timestamp usa o horário atual com fuso. Para simular outro sensor, altere `IDENTIFICADOR`.

| Gerador | Intervalos usados no sorteio |
|---|---|
| `manancial.py` | Percentual: 0–100%; nível: 0–30 m; temperatura da água: 5–35 °C. |
| `alagamento.py` | Nível: 0–500 cm; chuva: 0–100 mm; velocidade da água: 0–5 m/s. |
| `inversao_termica.py` | Umidade: 0–100%; temperatura: -5–40 °C; vento: 0–60 km/h. |

Os intervalos estão na função `gerar_leitura()`, nas chamadas `random.uniform()`. Os valores são arredondados para duas casas decimais. São dados simulados e independentes, sem modelo físico ou limites de alerta ambiental. Os intervalos de sorteio não substituem as regras de validação das APIs.

### Passo 3 — Iniciar os envios

Com a API correspondente iniciada no IntelliJ, execute **um comando por terminal**:

```powershell
python manancial.py
```

```powershell
python alagamento.py
```

```powershell
python inversao_termica.py
```

Cada gerador continua enviando até você pressionar **Ctrl+C**. O terminal mostra os dados enviados, a resposta HTTP e, ao encerrar, a quantidade de tentativas e gravações confirmadas.

Para enviar apenas dez requisições e encerrar automaticamente:

```powershell
python alagamento.py --quantidade 10
```

`--quantidade` limita as tentativas, inclusive as que falharem; não muda a frequência. Os envios são sequenciais e o tempo da resposta é descontado do intervalo. Se a API demorar mais que o intervalo, a taxa efetiva será menor. O script pula horários perdidos para evitar rajadas de requisições acumuladas.

Falhas de conexão e respostas de erro são exibidas, e o gerador continua na próxima tentativa programada. Uma falha de resposta não garante que a API deixou de gravar o dado. Consulte o banco quando precisar confirmar o resultado.

### Passo 4 — Limpar o banco quando desejar

O quarto arquivo, `limpar_banco.py`, serve para apagar as leituras acumuladas durante os testes. Ele não é um gerador e não possui frequência de envio.

| Variável | Para que serve |
|---|---|
| `BANCO` | Aponta para `dados/aps.db`, calculado a partir da localização do script. |
| `TABELAS` | Lista as três tabelas cujos registros serão apagados. |

Pare os geradores antes da limpeza, pois eles podem inserir novos registros logo depois. Na pasta `PYTHON`, execute:

```powershell
python limpar_banco.py
```

**Esse comando apaga imediatamente todos os registros das três tabelas, sem pedir confirmação.** Ele mostra o caminho do banco e a quantidade de registros apagados por tabela.

As tabelas, colunas, sequência dos IDs e arquivos de backup são preservados. Portanto, os próximos IDs não necessariamente começam em 1. A limpeza ocorre em uma única transação: se alguma exclusão falhar, toda a operação é desfeita. Se o arquivo não existir, o script informa o erro em vez de criar outro banco.

Depois, atualize a visualização do SQLite para conferir as tabelas vazias e execute novamente os geradores quando quiser produzir novas leituras.
