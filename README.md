# Transaction Authorizer

API de autorização de transações financeiras: consome eventos de abertura de conta de
uma fila AWS SQS e autoriza operações de crédito e débito sobre o saldo, com a
invariante de que um débito nunca deixa o saldo negativo.

## Execução local

Pré-requisitos: Docker e JDK 21. A versão 21 é o LTS alinhado ao que roda em
produção hoje, não uma versão presa por inércia: os recursos de que o serviço
depende (virtual threads, entre outros) já são estáveis nela.

```bash
# 1. Sobe Postgres, localstack, a topologia de filas e o gerador de 100k mensagens
docker compose up -d

# 2. Aguarda a mensagem "message-generator exited with code 0" nos logs
docker compose logs -f message-generator

# 3. Roda a aplicação — ela consome a fila e cria as contas
./gradlew bootRun
```

Health check: `curl http://localhost:8080/actuator/health`

O compose sobe quatro serviços: Postgres, localstack, um `sqs-configurator` que cria a
fila principal e a sua dead-letter queue com política de redrive, e o
`message-generator`, que semeia as 100 mil mensagens e termina. A aplicação nunca cria
filas: o que ela espera encontrar é criado por infraestrutura, aqui e em produção.

Toda a configuração tem padrão para execução local e é sobrescrevível por variável de
ambiente: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`, `SQS_ENDPOINT`,
`SQS_QUEUE_NAME`, `SQS_POLLERS`, `AWS_REGION`, `AWS_ACCESS_KEY_ID` e
`AWS_SECRET_ACCESS_KEY`. Em ambiente real, `SQS_ENDPOINT` fica vazio (o SDK resolve o
endpoint da região) e as chaves também, e aí a credencial vem da role da instância.

## Execução conteinerizada

A aplicação também roda como imagem, e um único comando sobe o sistema inteiro. O
serviço da aplicação fica atrás do profile `app`, então o compose padrão continua
servindo o fluxo com `bootRun`:

```bash
# Sobe dependências, semeia as mensagens e sobe a aplicação já conteinerizada
docker compose --profile app up --build
```

A imagem é multi-stage: build no JDK 21 e runtime num JRE slim, com o jar em camadas
para as dependências cacharem separadas do código, rodando como usuário sem privilégio.
O serviço espera o `message-generator` terminar antes de subir, então na primeira
execução já há mensagens para drenar.

## Observabilidade

- Logs estruturados em JSON no stdout, com `transactionId` na via HTTP e `messageId` na
  via de consumo para correlação.
- Métricas Prometheus em `/actuator/prometheus`, incluindo `authorizations`,
  `sqs_messages` e a saturação do pool HikariCP.
- Health em grupos: `/actuator/health/liveness` sem dependência,
  `/actuator/health/readiness` seguindo o banco, e o SQS como componente à parte.

## Autorização de transações

`POST /transactions/{transactionId}` autoriza um crédito ou débito contra o saldo. O
`transactionId` é gerado pelo cliente e é idempotente: reenviar o mesmo id devolve a
decisão original sem mover o saldo de novo.

```bash
curl -X POST "http://localhost:8080/transactions/$(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d '{"account_id":"<uuid da conta>","type":"DEBIT","amount":{"value":10.50,"currency":"BRL"}}'
```

Aprovação e recusa retornam ambas 200, diferindo no campo `transaction.status`
(`SUCCEEDED` ou `FAILED`): uma recusa por fundo insuficiente é uma decisão de negócio, não
um erro. Requisição malformada é 400, conta inexistente é 404, e moeda diferente de BRL
ou valor fora da faixa é 422, todos como `application/problem+json`. Contrato completo em
`docs/openapi.yaml`.

## Verificação

```bash
bin/ci    # formato, lint, testes e cobertura, o mesmo gate do CI
bin/e2e   # smoke de ponta a ponta sobre o sistema conteinerizado (Docker, curl e jq)
bin/chaos # derruba o Postgres no meio do consumo e prova a resiliência (Docker e curl)
```

`bin/e2e` sobe o sistema inteiro com o profile `app`, espera a semente e a readiness,
credita e debita uma conta semeada, confere a recusa por saldo, o replay idempotente e o
404, e derruba tudo ao final. É o ensaio do primeiro contato de quem chega pelo README,
rodado localmente antes de uma entrega. Não integra o `bin/ci` porque sobe containers e a
semente de 100 mil mensagens.

## Documentação

- `ROADMAP.md`: direção do projeto.
- `docs/openapi.yaml`: contrato HTTP da autorização.
- `docs/adr/`: decisões de arquitetura com motivadores e trade-offs.
