# ADR-008: Circuit breaker na porta de autorização

Status: aceito

## Contexto

Com o banco fora do ar, o custo não é a requisição que falha, é a requisição que **espera**.
A configuração anterior não fixava `connectionTimeout`, então valia o padrão de 30s do
HikariCP, e com threads virtuais ligadas (ADR-001) nada limita quantas requisições chegam:
não há pool de threads para saturar antes. No throughput medido em `docs/load/`, cerca de
2,7 mil requisições por segundo, meio minuto de espera significa dezenas de milhares de
requisições paradas ao mesmo tempo. O teto real deixa de ser o timeout e passa a ser o
limite de conexões do servidor HTTP, alcançado em segundos, e depois disso a recusa
acontece no TCP, sem resposta nenhuma. "O timeout do pool já limita a espera" é verdade e
não ajuda: ele limita em trinta segundos.

A readiness já cobre parte do problema, mas em outra escala de tempo. Ela é o sinal para o
balanceador, depende do intervalo da sonda e das falhas consecutivas que a plataforma
exige, e a própria sonda precisa de uma conexão do pool para responder. O buraco é a janela
entre o banco morrer e a instância sair de rotação.

A pergunta é a que separa os dois casos: **como distinguir uma fila legítima de pico de uma
dependência morta?** Os dois se parecem por dentro, uma fila que cresce.

## Decisão

Duas peças, e a ordem entre elas importa.

**Primeiro, `connectionTimeout` explícito de 3s.** O número sai da medição, não do gosto: o
cenário de pico em `docs/load/results.md` absorve um surto de dez vezes a base com latência
máxima de 1331, 1596 e 2105 ms nas três corridas, e **zero erro**. Uma fila legítima cabe
abaixo de 3s; uma dependência morta não termina nunca. Foi isso que descartou o ajuste
óbvio de baixar o timeout para algo como 500 ms: transformaria em erro um pico que hoje é
absorvido limpo. O timeout é um piso necessário porque o breaker só aprende com chamadas
que **terminam**: com 30s de espera, ele levaria 30s para abrir, que é exatamente o tempo
em que o estrago acontece.

**Segundo, um circuit breaker sobre a porta `TransactionStore`.** Passadas as primeiras
falhas de infraestrutura, ele abre e as requisições seguintes falham em microssegundos, sem
pegar conexão e sem ocupar thread, até a janela de prova reabrir o caminho. Depois de
alguns segundos ele deixa passar algumas requisições de prova e fecha sozinho se elas
funcionam.

O desenho, ponto a ponto:

- **Decorator sobre a porta, não anotação.** A costura já existe: a camada de aplicação
  depende da abstração e a suíte ArchUnit fixa a direção. O breaker entra como uma
  implementação de `TransactionStore` que embrulha a outra, é testável contra um dublê sem
  subir banco nem contexto Spring, e nenhuma biblioteca de resiliência atravessa para
  dentro da aplicação.
- **A biblioteca core, sem starter e sem AOP.** O starter carrega autoconfiguração e proxy
  para entregar uma anotação que este desenho não usa. Sem ele não há nada a supor sobre
  compatibilidade de starter com a versão do Spring Boot, e a configuração inteira do
  breaker fica visível em um `@Configuration`.
- **Não decorar o `DataSource`.** Seria mais fácil e pior: colocaria o breaker na frente do
  Flyway e do próprio health check, e um breaker aberto cegaria a sonda que deveria estar
  reportando a queda.
- **503 com `Retry-After`, nunca uma transação `FAILED`.** Uma recusa é uma decisão de
  negócio persistida, que carrega um id de transação e um saldo resultante. Um débito que
  não pôde ser verificado não é uma recusa, e devolvê-lo como tal entregaria ao cliente um
  veredito final que o serviço nunca alcançou. É a postura CP do ADR-007 aparecendo no
  contrato: sob partição o autorizador não decide, e diz que não decidiu.
- **Só exceções de infraestrutura contam.** `DataAccessResourceFailureException` (a queda
  de conexão), `QueryTimeoutException` e `CannotCreateTransactionException` (a falha em
  abrir a transação, que não é uma `DataAccessException` e, esquecida, manteria o breaker
  fechado durante justamente a queda que ele existe para tratar). Um breaker que contasse
  exceção esperada abriria sob tráfego saudável: o caminho de idempotência levanta
  `DuplicateKeyException` toda vez que duas requisições disputam o mesmo id de transação,
  que é o desenho funcionando.
- **Deliberadamente ausente do consumer SQS.** As mensagens são duráveis: o tempo de
  visibilidade, o backoff com full jitter e o `maxReceiveCount` já são o mecanismo (ADR-005).
  Um breaker ali só pararia de drenar uma fila que não tem pressa, e nada seria perdido nem
  ganho. Espalhar o padrão por toda dependência é o oposto de aplicá-lo.
- **A readiness continua seguindo o banco, não o breaker.** Se o breaker guiasse a
  readiness, todas as instâncias sairiam de rotação juntas, e não sobraria ninguém para
  responder 503 e dizer o que está acontecendo.

## Consequências

- Sob queda do banco, a resposta é imediata e correta em vez de ser uma espera longa
  seguida de erro. A quantidade de requisições em voo durante a queda passa a ser limitada
  pelos 3s do timeout, uma vez, em vez dos 30s anteriores a cada requisição.
- Um pico legítimo continua sendo absorvido: nada no caminho quente muda enquanto o breaker
  está fechado, e o custo por chamada é um contador em memória.
- O estado é observável: cada transição vai para o log como evento operacional e o gauge
  `authorizations_circuit_open` fica em 1 enquanto o breaker recusa chamadas, o que dá o
  alerta.
- Um pico com fila de conexão acima de 3s passa a virar erro onde antes virava espera. É
  degradação deliberada e limitada: trinta segundos de espera não são uma fila, são um
  acúmulo, e o cliente que espera trinta segundos já desistiu.
- `bin/chaos` deixa de provar só a resiliência do consumer e passa a provar também a da via
  HTTP, derrubando o banco e afirmando o 503 rápido.

## Alternativas consideradas

- **Só baixar o `connectionTimeout`, sem breaker**: é o ajuste mais barato e a medição o
  limita. Para bastar sozinho ele precisaria ser agressivo o suficiente para impedir o
  acúmulo, e aí quebra o cenário de pico que hoje passa com zero erro. Com um valor seguro
  para o pico, cada requisição ainda paga a espera inteira durante a queda, uma por uma,
  para descobrir o que a primeira já sabia.
- **Só o breaker, sem tocar no timeout**: o breaker aprende com chamadas que terminam, e
  com 30s de espera as primeiras chamadas do outage terminam 30s depois. Ele abriria tarde
  demais para evitar o acúmulo que motivou a decisão.
- **Confiar apenas na readiness e no balanceador**: é o sinal certo para tirar a instância
  de rotação e o errado para a primeira janela, porque depende da cadência da sonda e a
  sonda concorre pelo mesmo pool esgotado.
- **Anotação `@CircuitBreaker` no serviço de aplicação**: menos código e uma inversão de
  dependência perdida. Poria a biblioteca de resiliência dentro da camada que a arquitetura
  mantém livre de framework, e o teste passaria a exigir contexto Spring para exercitar o
  que hoje se prova com um dublê.
