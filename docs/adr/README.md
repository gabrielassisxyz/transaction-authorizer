# Decisões de arquitetura

Uma decisão por arquivo, numerada, escrita no mesmo PR que a implementa. Cada uma traz
o contexto, a decisão, as consequências e as alternativas que foram pesadas e recusadas.

| ADR | Decisão |
|---|---|
| [001](001-spring-mvc-com-virtual-threads.md) | Spring MVC com virtual threads, não WebFlux nem coroutines |
| [002](002-controle-de-concorrencia-do-saldo.md) | Controle de concorrência do saldo por atualização condicional atômica |
| [003](003-dinheiro-em-centavos-inteiros.md) | Dinheiro em centavos inteiros e moeda única |
| [004](004-idempotencia-da-autorizacao.md) | Idempotência da autorização por tabela de claims e hash da requisição |
| [005](005-consumer-sqs-com-sdk-v2-e-ack-por-mensagem.md) | Consumer SQS com AWS SDK v2, ack por mensagem e dead-letter queue |
| [006](006-semantica-http-da-recusa.md) | Semântica HTTP da recusa: recusa é 200, erro é ProblemDetail |
