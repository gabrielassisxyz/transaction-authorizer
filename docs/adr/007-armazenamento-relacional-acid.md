# ADR-007: Armazenamento relacional ACID para o livro-razão

Status: aceito

## Contexto

O serviço tem uma invariante que não admite exceção: o saldo nunca fica negativo, nem sob
débitos concorrentes sobre a mesma conta. Ao redor dela existem três escritas que precisam
valer ou falhar juntas em uma única autorização: a reserva do id da transação, que garante
a idempotência (ADR-004), a mutação do saldo e o registro da transação decidida. Se uma
delas vale e a outra não, o serviço passa a poder cobrar duas vezes pela mesma
requisição ou a perder o registro de um débito que já saiu do saldo.

A volumetria esperada é alta, e a tentação de escolher o armazenamento pela volumetria é
justamente o que essa invariante desautoriza: a decisão aqui é sobre qual classe de
armazenamento sustenta a garantia, e só depois sobre quanto ela escala.

## Decisão

O livro-razão vive em um banco **relacional ACID**, PostgreSQL, e a autorização inteira
acontece dentro de uma transação do banco.

A razão é direta: a invariante do saldo é uma restrição de integridade sobre **uma única
linha**, e o que ela pede é uma escrita condicional atômica, que um relacional oferece
como uma instrução (ADR-002) e ainda reforça com uma restrição declarada no esquema
(`CHECK (balance_cents >= 0)`), fora do alcance de qualquer código de aplicação. As outras
duas escritas entram na mesma transação sem custo de desenho: commit único, sem
coordenação, sem compensação.

O PostgreSQL, e não outro relacional, por causa do `RETURNING`: o caminho quente da
autorização é um `UPDATE ... WHERE ... RETURNING balance_cents`, uma viagem só ao banco
para decidir e já saber o saldo resultante. Em um motor sem `RETURNING` o mesmo caminho
custaria duas.

### Postura sob partição

Para o endpoint de autorização a escolha é **consistência sobre disponibilidade**. Quando
o banco está inalcançável, o autorizador **não decide**, e o que ele não pode fazer é
transformar isso em recusa. Uma recusa é uma decisão de negócio persistida, que carrega um
id de transação e um saldo resultante; um débito que não pôde ser verificado não é uma
recusa, e devolvê-lo como tal entregaria ao cliente um veredito final que o serviço nunca
alcançou. Uma dependência indisponível sai por um código de erro, o 503 do ADR-008, e não
por um `status` no corpo.

A disponibilidade é recuperada por redundância na camada do banco, réplica e failover, não
relaxando a invariante. É a troca que a natureza do serviço impõe: aprovar um débito que
não se consegue verificar é pior, para um autorizador, do que não responder.

A via de criação de contas tem a postura oposta e por bom motivo: as mensagens são
duráveis na fila, então uma indisponibilidade do banco só adia o consumo, sem perder nada
(ADR-005). A postura é por caminho, não global.

## Consequências

- O teto de escrita é o de um nó, e ele foi medido, não estimado: cerca de 2,7 mil
  autorizações por segundo com p99 abaixo de 92 ms, com o pool de conexões como o
  controle de concorrência real e a curva de saturação levantada por varredura
  (`docs/load/`). A escolha não é um palpite conservador, é um teto conhecido.
- Concentrar tráfego em poucas contas serializa no lock de linha, e isso aparece na
  medição: o throughput cai cerca de um terço com dez contas quentes. É o custo da
  invariante, e ele é local à linha disputada, não global ao serviço.
- Escrita multi-região fica fora do desenho atual. O serviço é sem estado, então quem
  decide a estratégia é o banco; ativo-passivo com réplica promovível é o passo natural, e
  ativo-ativo exigiria repensar a serialização do saldo (`ROADMAP.md`).
- O esquema é versionado com Flyway e as migrações rodam no start da aplicação, o que
  amarra o formato do livro-razão ao artefato que o usa.

## Alternativas consideradas

- **Chave-valor com escrita condicional (DynamoDB e similares)**: cobre a parte mais
  citada do problema, porque uma atualização condicional sobre um único item é atômica e
  sustentaria o saldo nunca-negativo sozinha. O que ela não cobre barato é o resto da
  autorização: a reserva de idempotência, o saldo e o registro da transação são três itens,
  e mantê-los atômicos exige transação multi-item, com limites e custo próprios, ou um
  fluxo de compensação escrito à mão. Paga-se complexidade de aplicação para recuperar o
  que o relacional dá em um commit.
- **Armazenamento eventualmente consistente com reserva e compensação**: o padrão seria
  reservar o valor, confirmar depois e compensar no erro. Compra disponibilidade sob
  partição e paga com uma janela em que o saldo exibido não é o saldo comprometido, além de
  um caminho de compensação que precisa ser correto sob falha, que é o caminho que menos se
  testa. Para um autorizador, a troca é na direção errada.
- **Relacional distribuído (CockroachDB, Spanner, Aurora e afins)**: mantém SQL e
  transações e resolve a escrita multi-região, ao custo de latência de consenso por
  transação e de uma superfície operacional muito acima do que um serviço deste tamanho
  justifica hoje. É o passo seguinte se a operação multi-região virar requisito, não a
  partida.
- **Estado em memória com snapshot periódico**: o mais rápido de todos e o único que perde
  a decisão. Uma autorização respondida precisa sobreviver à queda do processo, e é
  exatamente isso que a durabilidade do commit garante.
