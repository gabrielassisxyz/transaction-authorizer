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
- **Autorização:** `POST /transactions/{transactionId}` com crédito e débito sob a
  invariante de saldo nunca-negativo, garantida por update condicional atômico;
  idempotência reservada antes de qualquer mutação de saldo; todos os corner cases
  testados, incluindo dois débitos concorrentes na mesma conta.
- **Production-ready:** espera com full jitter antes de nova tentativa no consumer,
  métricas em endpoint Prometheus, logs JSON com correlação, grupos de health separando
  banco e fila, imagem conteinerizada e smoke de ponta a ponta.
- **Narrativa de operação:** OpenAPI e coleção de requisições, `docs/failure-modes.md`
  por componente, diagrama de deploy em cloud pública e proposta de pipeline canário.
- **Prova de carga:** campanha k6 com gerador e SUT em máquinas isoladas, três corridas
  por cenário. Regime, pico e concentração em conta quente medidos em `docs/load/`, com a
  curva de saturação do pool e o tamanho do pool confirmado por varredura.

## Próximos passos

1. **Revisão sênior às cegas.** Um par de olhos que não escreveu o código percorre o
   repositório a partir de um clone limpo e aplica a janela de correção que apontar.

## Com mais tempo

Direções que o desenho atual já comporta e que um horizonte maior justificaria:

- **Outbox para efeitos colaterais exactly-once:** hoje a decisão é durável no banco, mas
  uma notificação ou publicação de evento a jusante seria at-least-once. Uma tabela de
  outbox escrita na mesma transação da autorização, drenada por um relay, fecharia isso.
- **Testes de contrato:** o esquema de requisição é uma suposição derivada da resposta
  exigida. Um teste de contrato contra o produtor real da fila e contra os consumidores da
  API travaria o formato antes de uma quebra chegar a produção.
- **Postura multi-região:** o serviço é sem estado, então o que decide a estratégia é o
  banco. Ativo-passivo com réplica de leitura promovível é o passo natural; ativo-ativo
  exigiria repensar a serialização do saldo, que hoje é local ao Postgres.

## Fora de escopo

- Extrato bancário, consulta de saldo como endpoint dedicado, múltiplas moedas,
  matching/settlement: o serviço é um autorizador, não um core bancário completo.
