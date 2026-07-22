# Roadmap

Direção do projeto: o que existe, o que vem a seguir e o que fica fora de escopo.

## Feito

- **Estrutura do repositório:** gates determinísticos (gitleaks, gate de prosa, CI),
  spec de convenções (`AGENTS.md`), docker-compose com Postgres, localstack SQS,
  topologia de filas com dead-letter queue e o gerador de 100 mil mensagens.
- **Fundações:** esquema do livro-razão versionado com Flyway (contas, reservas de
  idempotência, transações), dinheiro em centavos inteiros e a suíte ArchUnit que
  fixa a direção das dependências da arquitetura hexagonal.
- **Criação de contas via SQS:** consumer idempotente com ack por mensagem,
  classificação de veneno contra falha transitória, redrive para a dead-letter queue,
  detecção de duplicata divergente e desligamento que espera as mensagens em voo.

## Próximos passos

1. **Autorização.** `POST /transactions/{transactionId}` com crédito e débito sob a
   invariante de saldo nunca-negativo, garantida por update condicional atômico;
   idempotência reservada antes de qualquer mutação de saldo; todos os corner cases
   testados, incluindo dois débitos concorrentes na mesma conta.
2. **Production-ready.** Espera com full jitter antes de nova tentativa no consumer;
   métricas expostas em endpoint Prometheus; logs JSON com correlação; grupos de
   health separando banco e fila; OpenAPI e coleção de requisições.
3. **Narrativa de operação.** Diagrama de deploy em cloud pública; proposta de
   pipeline com estratégia de deploy de risco limitado (blue/green ou canary);
   `docs/failure-modes.md`.
4. **Prova de carga.** Cenário k6 com gerador e servidor em máquinas isoladas;
   throughput e p99 documentados em `docs/load/`.

## Fora de escopo

- Extrato bancário, consulta de saldo como endpoint dedicado, múltiplas moedas,
  matching/settlement: o serviço é um autorizador, não um core bancário completo.
