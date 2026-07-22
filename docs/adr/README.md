# Decisões de arquitetura

Uma decisão por arquivo, numerada, escrita no mesmo PR que a implementa. Cada uma traz
o contexto, a decisão, as consequências e as alternativas que foram pesadas e recusadas.

| ADR | Decisão |
|---|---|
| [001](001-spring-mvc-com-virtual-threads.md) | Spring MVC com virtual threads, não WebFlux nem coroutines |
| [003](003-dinheiro-em-centavos-inteiros.md) | Dinheiro em centavos inteiros e moeda única |
| [005](005-consumer-sqs-com-sdk-v2-e-ack-por-mensagem.md) | Consumer SQS com AWS SDK v2, ack por mensagem e dead-letter queue |

Os números ausentes estão reservados para decisões cuja implementação ainda não
chegou, e ficam com o número desde já para que o texto que já as referencia não precise
ser renumerado: 002 para o controle de concorrência do saldo, 004 para a idempotência
da autorização, 006 para a semântica HTTP da recusa.
