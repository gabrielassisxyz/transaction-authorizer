# ADR-005: Consumer SQS com AWS SDK v2, ack por mensagem e dead-letter queue

Status: aceito

## Contexto

A criação de contas chega por uma fila SQS com entrega *at-least-once*: a mesma
mensagem pode chegar mais de uma vez, e uma mensagem malformada pode chegar para
sempre. O consumer precisa decidir três coisas independentes: qual cliente usar, quando
apagar a mensagem, e o que fazer com a que nunca vai ser processada.

## Decisão

### Cliente: `SqsClient` do AWS SDK v2, com poller próprio

A tabela de compatibilidade do Spring Cloud AWS lista a linha 4.x contra Spring Boot
**4.0.x**. O projeto está em Boot 4.1, então adotá-lo seria apostar em uma combinação
que o próprio projeto não declara suportar, e o preço de errar é descobrir o problema
no meio da fatia seguinte. O SDK v2 sozinho tem risco de compatibilidade zero e o que
se perde é um poller pronto: cerca de cem linhas, que é justamente onde estão as
decisões abaixo.

Os pollers rodam em threads virtuais, coerente com o modelo único de concorrência do
ADR-001. O número é configurável e começa em dois; dimensioná-lo antes de medir seria
chute, e o gargalo real é o pool de conexões.

### Ack por mensagem, imediatamente após o commit

Cada mensagem é apagada assim que o seu próprio trabalho no banco commita, nunca em
lote no fim da iteração. Um lote de dez mensagens em que a quinta falha, com ack no
fim, devolveria as quatro já processadas para a fila, e um banco degradado viraria uma
tempestade de reentrega de trabalho já feito, exatamente no pior momento.

### Falha malformada e falha transitória são coisas diferentes

- **Mensagem que não parseia** é veneno: não adianta tentar de novo, o conteúdo não vai
  mudar. Ela **não é apagada**. Vence a visibilidade, volta, e depois de
  `maxReceiveCount` recebimentos o redrive a leva para a dead-letter queue. Apagá-la
  ali destruiria a evidência; o redrive é o orçamento de tentativa, e a DLQ é onde
  alguém consegue olhar para ela.
- **Falha transitória** (banco fora, timeout) também não apaga a mensagem, mas o poller
  espera antes da próxima tentativa em vez de girar em vazio contra um banco que já
  está sofrendo.

### `maxReceiveCount` de 5

É orçamento, não contagem de retentativa: o contador de recebimentos **nunca é
zerado**, então precisa absorver uma indisponibilidade curta de banco sem mandar
mensagem válida para a DLQ. Três seria agressivo demais para isso; um número alto
demais faria mensagem envenenada consumir recebimentos por muito tempo.

### Faixa plausível de `created_at`

O parser aceita três formas para o campo, e nenhuma delas é distinguível das outras
pelo tipo: segundos desde a época em string, segundos como número JSON, e ISO-8601.
Isso deixa um erro mudo em aberto, o de mil vezes: `"1751000000000"` é milissegundos
onde o contrato promete segundos, e vira uma data no ano 57488 sem nenhum aviso.

Um evento de abertura de conta descreve algo que já aconteceu, então data no futuro
não é leitura plausível de forma nenhuma. Essa é regra de domínio, não heurística de
faixa, e é o que permite recusar sem inventar limite que o contrato não dá. A recusa
classifica a mensagem como veneno e a manda para o orçamento de redrive.

A tolerância de cinco minutos existe porque duas máquinas nunca concordam no segundo.
Sem ela, desvio normal de relógio mandaria evento válido para a dead-letter queue, que
é exatamente o tipo de recusa que faz alguém desligar a validação.

### Duplicata exata e duplicata divergente

A criação é idempotente por `INSERT ... ON CONFLICT (id) DO NOTHING`. Quando o conflito
acontece, o dono e o status armazenados são comparados com os do evento:

- iguais: redundância normal de entrega *at-least-once*, contabilizada e ignorada;
- diferentes: **anomalia**. O primeiro registro é mantido, mas o caso vira log de aviso
  e a métrica `accounts.conflict`. Tratar isso como duplicata normal esconderia um
  defeito real de quem produz o evento.

### Desligamento

Ao parar, o consumer deixa de aceitar novos recebimentos e espera os pollers em voo
terminarem, com timeout maior que a espera do long polling. Cortar antes abandonaria
mensagens já recebidas e ainda não commitadas, e todo deploy fabricaria processamento
duplicado e crescimento enganoso da DLQ.

## Consequências

- O poller é código do projeto, e portanto responsabilidade do projeto: o laço precisa
  ser à prova de exceção, porque uma exceção escapando encerra aquele poller em
  silêncio pelo resto da vida do processo.
- A topologia de filas passa a ser pré-requisito de execução, não algo que a aplicação
  cria. Localmente isso é um serviço one-shot no compose; em produção é
  infraestrutura como código. O ganho é que um nome de fila errado falha alto, em vez
  de produzir uma fila vazia que ninguém lê.
- A espera antes de nova tentativa é fixa nesta fatia. Sob falha correlacionada, todos
  os pollers voltam juntos; o *backoff* com full jitter que resolve isso entra na fatia
  de resiliência.

## Alternativas consideradas

- **Spring Cloud AWS (`@SqsListener`)**: entregaria poller, ack e conversão prontos, e
  seria a escolha imediata em um projeto sobre Boot 4.0. Fica registrado como o caminho
  natural quando a compatibilidade com 4.1 for publicada.
- **Apagar a mensagem malformada na hora**: mais simples e evita ruído de reentrega, mas
  troca a evidência por silêncio: um defeito no produtor de eventos passaria a ser
  invisível.
- **Ack em lote no fim da iteração**: menos chamadas de API, ao custo de reprocessar
  mensagens já concluídas sempre que uma falhar no meio do lote.
