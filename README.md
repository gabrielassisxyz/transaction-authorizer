# Transaction Authorizer

API de autorização de transações financeiras: consome eventos de abertura de conta de
uma fila AWS SQS e autoriza operações de crédito e débito sobre o saldo, com a
invariante de que um débito nunca deixa o saldo negativo.

## Execução local

Pré-requisitos: Docker e JDK 21.

```bash
# 1. Sobe localstack (fila SQS semeada com 100k contas) e Postgres
docker compose up -d

# 2. Aguarda a mensagem "message-generator exited with code 0" nos logs
docker compose logs -f message-generator

# 3. Roda a aplicação
./gradlew bootRun
```

Health check: `curl http://localhost:8080/actuator/health`

## Verificação

```bash
bin/ci   # formato, lint, testes e cobertura — o mesmo gate do CI
```

## Documentação

- `ROADMAP.md`: direção do projeto.
- `docs/adr/`: decisões de arquitetura com motivadores e trade-offs.
