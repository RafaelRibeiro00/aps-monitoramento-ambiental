# APS — APIs Java para testar pelo Postman

O projeto contém somente as três APIs de ingestão e seus modelos/validações. Os sensores simulados e suas configurações de execução foram removidos. Você envia os dados pelo Postman ou outro cliente HTTP.

## 1. Iniciar no IntelliJ

Abra o `pom.xml` da pasta APS como projeto Maven e selecione **JDK 17**. Nesta máquina, o JDK está em `C:\Users\Rafae\.jdks\ms-17.0.20`; o comando java do terminal aponta para Java 8, então selecione o 17 no editor.

Aguarde a sincronização do Maven. Execute e mantenha abertas estas três configurações:

| Configuração no IntelliJ | Porta | Endereço para POST |
|---|---:|---|
| API - Manancial | 8081 | http://127.0.0.1:8081/leituras |
| API - Alagamento | 8082 | http://127.0.0.1:8082/leituras |
| API - Inversao Termica | 8083 | http://127.0.0.1:8083/leituras |

Cada API fica aguardando requisições até você apertar **Stop**. Para iniciar sem usar as configurações prontas, abra `Api.java` do módulo desejado e clique no triângulo verde ao lado de `main`.

Os endereços são para o Postman executado no mesmo computador das APIs. No Postman web, use o Desktop Agent para acessar a máquina local. Não é necessário login na API: selecione **No Auth** na requisição.

## 2. Enviar pelo Postman

O arquivo `APS.postman_collection.json`, na pasta APS, já contém as três requisições. No Postman, clique em **Import**, escolha esse arquivo e abra uma das requisições importadas. Com a API correspondente em execução, clique em **Send**.

Se preferir montar manualmente:

1. Selecione o método **POST**.
2. Cole o endereço correspondente da tabela.
3. Selecione **Body → raw → JSON**.
4. Use o cabeçalho `Content-Type: application/json` (o Postman o configura ao selecionar JSON).
5. Cole um dos corpos abaixo e clique em **Send**.

### Manancial — porta 8081

```json
{
  "area": "AREA-001",
  "timestamp": "2026-09-04T21:30:00-03:00",
  "percentual_ocupado": 35.5
}
```

### Alagamento — porta 8082

```json
{
  "sensor": "SENSOR-001",
  "timestamp": "2026-09-04T21:30:00-03:00",
  "nivel_corrego_cm": 120.0
}
```

O campo `sensor` é somente o identificador da origem da leitura; não existe programa de simulação no projeto.

### Inversão térmica — porta 8083

```json
{
  "estacao": "ESTACAO-001",
  "timestamp": "2026-09-04T21:30:00-03:00",
  "umidade": 68.5
}
```

É possível usar essas datas de exemplo: a API verifica o formato, não exige que a data seja a atual. O sufixo `-03:00` indica o fuso; `Z` também é aceito para UTC. Use ponto nos números decimais e não coloque aspas nos valores numéricos.

Referências do Postman: [enviar corpo JSON](https://learning.postman.com/docs/use/send-requests/create-requests/parameters/) e [importar coleção](https://learning.postman.com/docs/getting-started/importing-and-exporting/importing-data/).

## 3. Respostas e validação da conexão

Com um JSON válido, o Postman recebe **200 OK**:

```json
{"mensagem":"Leitura recebida com sucesso."}
```

O console da API mostra `Leitura recebida` com os dados enviados. Isso confirma o fluxo completo de conexão HTTP e recebimento.

Para testar um erro, altere a umidade para `101` na requisição de inversão térmica. A resposta deve ser **400 Bad Request**:

```json
{"erro":"umidade deve ser um número entre 0 e 100, inclusive."}
```

As três APIs exigem exatamente os três campos do tema. A identificação deve ser um texto não vazio, a data/hora deve estar em RFC 3339, percentual e umidade devem ficar entre 0 e 100, e nível do córrego deve ser maior ou igual a zero. Zero é válido; medida ausente ou null é rejeitada.

- **200:** leitura recebida e válida.
- **400:** conexão funcionou, mas o JSON ou algum dado precisa ser corrigido; consulte o campo `erro`.
- **404:** conexão funcionou, mas o caminho está errado; use `/leituras`.
- **405:** conexão funcionou, mas o método está errado; use POST. Abrir a URL no navegador normalmente envia GET.
- **ECONNREFUSED / Could not send request:** confira se a API está iniciada, se a porta está correta e se está usando um cliente local (aplicativo Postman ou Desktop Agent).

### Estrutura e testes

Cada módulo mantém `Api.java`, `Leitura.java`, `ApiTest.java` e seu `pom.xml`. Não há banco de dados, análise ambiental ou envio automático. Os dados recebidos são apenas validados e exibidos no console.

Os testes automatizados verificam as rotas por HTTP, incluindo campos ausentes, tipos incorretos, zero válido, limites e JSON inválido. Para executá-los, use **Run 'ApiTest'** no IntelliJ ou **Lifecycle → test** na janela Maven.
Verificação desta versão: **108 testes Java aprovados (36 por API)**. Também foram testadas as três APIs simultaneamente por um cliente HTTP externo, usando os corpos exatos da coleção Postman: as portas 8081, 8082 e 8083 responderam 200 para dados válidos e 400 para medidas negativas. A interface do Postman não foi automatizada. Os servidores de teste foram encerrados; inicie as configurações de API no IntelliJ antes de enviar pelo Postman.
