# ADR-001: Spring MVC com virtual threads, não WebFlux nem coroutines

Status: aceito

## Contexto

O serviço é I/O-bound: cada autorização é essencialmente uma ida ao Postgres, e o
consumer de SQS é uma sucessão de chamadas de rede. O requisito de escala pede que
milhares de requisições concorrentes não fiquem presas em um pool de threads de
plataforma. Existem três formas de chegar lá na JVM: WebFlux (reativo), coroutines
Kotlin, ou threads virtuais (Loom) sob o modelo servlet tradicional.

## Decisão

Spring MVC com `spring.threads.virtual.enabled=true`. Um único modelo de
concorrência no projeto inteiro: código imperativo, bloqueante, executado em threads
virtuais.

## Consequências

- O código de negócio permanece linear. Stack traces são legíveis, o depurador
  funciona, e `try`/`catch` e transações do Spring se comportam como qualquer
  desenvolvedor Java espera. Nada de operadores reativos nem de contexto propagado
  manualmente.
- O limitador de concorrência deixa de ser o pool de threads e passa a ser o **pool de
  conexões** (HikariCP). Isso é uma mudança real de comportamento sob carga: a fila
  passa a se formar na aquisição de conexão. Por isso o tamanho do pool é declarado
  explicitamente (`spring.datasource.hikari.maximum-pool-size`, padrão 20) em vez de
  herdado, e a saturação do pool é a métrica a observar na campanha de carga.
- Em Java 21, um bloco `synchronized` que bloqueia fixa (*pins*) a thread virtual à
  thread de plataforma. Hikari e Hibernate modernos usam `ReentrantLock`, então o
  caminho quente não fixa; qualquer biblioteca adicionada ao caminho de I/O precisa
  ser avaliada sob essa ótica até o projeto migrar para Java 24+.
- Não há suporte a *backpressure* de ponta a ponta como no WebFlux. Para este serviço
  isso não é perda: o backpressure útil aqui é o do banco, e ele se manifesta como
  espera no pool.

## Alternativas consideradas

- **WebFlux**: entregaria a mesma escala, mas exige toda a stack reativa:
  R2DBC no lugar de JPA, um SDK reativo para SQS, e um custo de legibilidade e de
  depuração que só se paga em cargas com *streaming* ou com composição pesada de
  chamadas. Nenhum dos dois é o caso.
- **Coroutines**: resolveriam a concorrência com boa ergonomia em Kotlin, mas
  conviveriam com o modelo bloqueante do JDBC e do SDK da AWS, exigindo pontes
  (`withContext(Dispatchers.IO)`) que reintroduzem justamente o pool de threads que
  se queria evitar. Dois modelos de concorrência no mesmo projeto é o pior dos
  cenários para manutenção.
