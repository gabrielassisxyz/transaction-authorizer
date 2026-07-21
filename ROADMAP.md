# Roadmap

Direção do projeto: o que existe, o que vem a seguir e o que fica fora de escopo.

## Feito

- Estrutura do repositório: gates determinísticos (gitleaks, CI), spec de convenções
  (`AGENTS.md`), docker-compose com localstack SQS e Postgres.

## Próximos passos

1. **MVP correto** — consumer SQS cria contas (saldo zero, idempotente);
   `POST /transactions/{transactionId}` autoriza crédito/débito com a invariante de
   saldo nunca-negativo (update condicional atômico); dinheiro em centavos inteiros;
   todos os corner cases testados, incluindo dois débitos concorrentes na mesma conta.
2. **Production-ready** — retry/backoff com full jitter + DLQ no consumer; métricas
   (Micrometer/Prometheus); logs JSON; OpenAPI + coleção de requisições.
3. **Narrativa de operação** — diagrama de deploy em cloud pública; proposta de
   pipeline com estratégia de deploy de risco limitado (blue/green ou canary);
   `docs/failure-modes.md`; ADRs das decisões principais.
4. **Prova de carga** — cenário k6 com gerador e servidor isolados; throughput e p99
   documentados em `docs/load/`.

## Fora de escopo

- Extrato bancário, consulta de saldo como endpoint dedicado, múltiplas moedas,
  matching/settlement — o serviço é um autorizador, não um core bancário completo.
