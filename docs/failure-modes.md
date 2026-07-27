# Modos de falha

Por componente, o que acontece quando ele falha, o que o serviço faz a respeito e o que
um operador vê. A invariante que atravessa tudo: uma falha de dependência degrada o
serviço, nunca corrompe o saldo nem perde uma mensagem válida.

## SQS indisponível

A fila fica inalcançável (rede, credencial, indisponibilidade do serviço).

- **Consumo:** o poller trata a falha de `ReceiveMessage` como transitória e recua com
  full jitter, dormindo um intervalo aleatório em `[0, min(cap, base * 2^tentativas)]`,
  com base de 1s e teto de 30s. Full jitter, em vez de jitter parcial, para que pollers
  que falharam juntos não acordem juntos e martelem a fila assim que ela volta. Nenhuma
  mensagem é perdida: nada foi recebido para perder.
- **Via HTTP:** intacta. A autorização não toca a fila, então crédito e débito seguem
  atendendo enquanto o consumo espera.
- **Sinal:** o componente de health do SQS fica vermelho e o gauge `sqs_connectivity` cai
  para zero. Esse componente fica de fora da readiness de propósito: uma fila fora do ar
  precisa alertar sem tirar o autorizador do balanceador, já que a via HTTP continua sã.

## Banco indisponível

O Postgres cai ou fica inalcançável.

- **Via HTTP, no instante da queda:** cada requisição espera no máximo 3s por uma conexão
  e recebe 503 com `Retry-After`, nunca uma recusa. O circuit breaker que envolve a
  porta de autorização abre depois das primeiras falhas de infraestrutura e, a partir daí,
  as requisições falham em microssegundos, sem ocupar conexão nem thread. É o que impede
  que o serviço acumule requisições paradas enquanto o sinal mais lento abaixo não chega
  (ADR-008).
- **Via HTTP, na escala de segundos:** a readiness (`/actuator/health/readiness`) segue o
  banco e fica vermelha, então o balanceador tira a instância de rotação. Esse sinal é
  necessário e é lento por construção: ele depende do intervalo da sonda e do número de
  falhas consecutivas que a plataforma exige, e a própria sonda precisa de uma conexão do
  pool para responder. O breaker é o sinal in-process que cobre a janela até lá. A liveness
  não tem dependência e continua verde, então a plataforma não reinicia o processo e
  transforma uma queda de dependência em cascata de reinícios.
- **Consumo:** a criação de conta falha ao escrever, o poller trata como transitório e
  não apaga a mensagem. Sem o delete, a mensagem volta pela fila quando a visibilidade
  expira. O orçamento antes de a mensagem ir para a dead-letter queue é `maxReceiveCount`
  vezes `VisibilityTimeout`, cinco recebimentos a trinta segundos, ou seja da ordem de
  dois minutos de indisponibilidade. Vale expressá-lo em tempo e não em contagem, porque
  é assim que ele se compara com a falha que precisa sobreviver: a janela de um failover de
  banco entre zonas é da mesma ordem de grandeza (`scale.md`), e nada no repositório mediu
  qual das duas é maior. Uma queda de banco mais longa que esse orçamento manda para a
  dead-letter queue uma mensagem válida, e não há nada de errado com ela: o
  `maxReceiveCount` não distingue corpo inválido de banco fora do ar.
- **Recuperação:** quando o banco volta, o breaker deixa passar algumas requisições de
  prova depois da janela aberta e fecha sozinho se elas funcionam, sem intervenção e sem
  reinício; a readiness fica verde de novo, a instância retorna à rotação e as mensagens que
  voltaram para a fila são consumidas. A criação é idempotente, então uma mensagem que
  chegou a ser processada antes da falha não cria conta em duplicidade.

## Crescimento da dead-letter queue

Uma mensagem que falha em ser processada além do `maxReceiveCount` sai da fila principal
para a dead-letter queue.

- **O que chega lá:** mensagem veneno, aquela cujo corpo não parseia. Ela não é apagada
  ao ser detectada, de propósito: a política de redrive é o orçamento de retentativa, e a
  mensagem tem que alcançar a dead-letter queue em vez de morrer silenciosamente no
  consumer. Uma falha transitória repetida também pode esgotar o orçamento e cair lá.
- **Evidência:** cada descarte registra `messageId` e o `ApproximateReceiveCount` no log,
  e o contador `sqs_messages_total{outcome="poison"}` sobe. A dead-letter queue tem retenção
  de 14 dias, mais longa que os 4 dias da fila principal, porque quem investiga precisa de
  um tempo que a origem já gastou.
- **Resposta operacional:** inspecionar a mensagem, corrigir a causa (um produtor que
  emite corpo inválido, um esquema que mudou) e devolver a mensagem da dead-letter queue
  para a principal. Nenhuma correção acontece automaticamente: a dead-letter queue é um ponto de
  parada para decisão humana, não um caminho de retentativa.

## Tempestade de duplicatas

O mesmo evento ou a mesma transação chega muitas vezes, por replay de produtor, por
redrive ou por reentrega da própria fila.

- **Criação de conta:** idempotente pela chave primária da conta. Uma mensagem repetida
  cria a conta uma vez; as repetições viram no-op e são apagadas.
- **Autorização:** o id da transação é reservado numa tabela de claims antes de qualquer
  mutação de saldo, então um replay encontra a decisão gravada e a devolve sem mover o
  saldo de novo. Um id reusado com um payload diferente mantém a primeira decisão e
  incrementa `transactions_duplicate_payload_total`, porque isso é sinal de defeito no cliente,
  não uma retentativa legítima.

## Morte do poller no meio de uma mensagem

O processo cai, ou é reiniciado, depois de receber uma mensagem e antes de apagá-la.

- **O que acontece:** a mensagem não foi apagada, então o tempo de visibilidade expira e
  ela volta para a fila e é reentregue a outro poller. A idempotência da criação absorve a
  reentrega: se a escrita da primeira tentativa chegou a commitar, a segunda é no-op.
- **Desligamento gracioso:** um `stop` ordenado espera as mensagens em voo terminarem
  antes de derrubar os pollers, com um timeout que ultrapassa o long poll de 20s, para não
  abandonar mensagem recebida e ainda não commitada. Só um `kill` abrupto cai no caminho
  de reentrega acima.

## Fidelidade do localstack ante o SQS real

A topologia de filas, o redrive e a dead-letter queue são exercitados contra o localstack,
não contra o SQS gerenciado.

O localstack implementa a semântica de redrive, visibilidade e `maxReceiveCount` bem o
suficiente para os testes de integração, mas não é o serviço real. Nuances de contagem de
recebimento sob concorrência alta, de latência de propagação de atributos e de limites de
lote podem diferir na AWS. O comportamento aqui documentado foi verificado contra o
emulador; a diferença ante o serviço gerenciado é reconhecida em vez de escondida, e uma
homologação em ambiente real é o passo que a fecharia.
