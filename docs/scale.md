# Capacidade e escala

Quantas instâncias este serviço comporta, o que limita esse número, e o que muda no desenho
quando o volume cresce dez e cem vezes. A campanha em [`load/`](load/) mede uma instância;
este documento é a leitura dela para uma frota.

## O que a medição diz sobre conexões

A varredura refeita em `load/results.md` mediu o throughput de uma instância variando o
tamanho do pool com a carga oferecida mantida constante, que é a condição sem a qual o pool
não é o limitante:

| Pool | Conexões ativas | Saturação | Fila | Throughput (req/s) | p99 (ms) |
|---|---|---|---|---|---|
| 20 | 19,7 de 20 | 98,6% | 134 | 3115 | 294 |
| 80 | 78,4 de 80 | 98,0% | 72 | 4054 | 163 |

**Mais conexões entregam mais vazão e uma cauda melhor**, e o resultado foi replicado três
vezes ao longo da campanha, com ganhos de 32%, 23% e 30%. Não existe joelho até 80: o pool
não é um número a minimizar, e a única degradação medida está no outro extremo, em 10, onde
ele estrangula.

Isso remove uma restrição que uma leitura anterior desta campanha havia inventado. O que
**não** remove é a restrição real: conexões são um recurso finito e compartilhado do banco.
O `max_connections` do RDS é função da memória da classe de instância, e cada instância da
aplicação leva o seu pool inteiro para dentro desse orçamento comum.

## Aritmética da frota

Com `DB_POOL_SIZE=20`, cada instância abre até 20 conexões, então N instâncias consomem
20 × N do orçamento do banco. A conta é trivial e não está feita em lugar nenhum: **a
topologia de deploy desenha um balanceador sobre "múltiplas tasks" e nunca diz quantas
cabem**, nem nomeia a classe de instância que decide o teto.

Duas ressalvas de honestidade sobre o que a campanha autoriza afirmar:

- A varredura variou o pool de **uma** instância. Tratar 80 conexões vindas de quatro
  instâncias como equivalentes a 80 vindas de uma é razoável, porque o Postgres não distingue
  a origem, mas segue sendo suposição. A medição que decide é duas instâncias reais contra o
  mesmo banco, e ela não foi feita.
- O que a medição **descarta** é a ideia de que somar instâncias derrube a vazão por
  contenção. Até 80 conexões saturadas, com 35% de CPU ociosa no host, o sistema ainda estava
  ganhando com mais paralelismo.

O limite prático, então, não é uma curva que vira: é um orçamento que acaba. Ele merece ser
declarado com um número, e o número depende de uma classe de instância que a topologia ainda
não escolheu.

## O teto tem prazo de validade

O achado mais consequente da campanha refeita não é sobre conexões. Seis corridas de
configuração idêntica, espalhadas ao longo da campanha, com o livro-razão crescendo entre
elas:

| Linhas | Throughput (req/s) |
|---|---|
| 0 | 6202 |
| 4,7M | 5318 |
| 7,7M | 4193 |
| 8,9M | 3753 |
| 11,1M | 3244 |
| 12,0M | 3115 |

**Metade da vazão perdida ao ir de zero a doze milhões de linhas**, sem mudar configuração
nenhuma. As duas tabelas de maior escrita são append-only com chave primária aleatória, então
cada inserção cai numa folha diferente do índice e o custo cresce com o tamanho da tabela. A
CPU do host não acompanha a queda, o que aponta para I/O de índice.

Isso muda o que significa "capacidade" neste serviço. Um número de throughput só quer dizer
alguma coisa acompanhado do tamanho da base em que foi medido, e o crescimento do livro-razão
é uma variável de capacidade tanto quanto a contagem de instâncias. Em doze horas do volume
que a campanha sustenta, a base cresce mais do que cresceu durante toda ela. A resposta para
isso não é mais réplica: é retenção, particionamento por tempo com arquivamento, e uma chave
primária ordenada no tempo para as duas tabelas de alto insert.

## Um pool para duas vias

O orçamento de conexão de uma instância não é gasto só pela autorização. O adaptador de
persistência da autorização e o da criação de conta injetam o mesmo `JdbcClient`, então a
via HTTP e o consumer SQS disputam as **mesmas 20 conexões**.

Em regime normal isso não aparece, porque a semente drena e o consumo fica ocioso. Aparece
no pior momento possível: um redrive grande, ou uma reprocessagem em massa da fila, consome
conexões que a autorização precisa, e o efeito é latência na via que tem cliente esperando
por causa de trabalho que não tinha pressa nenhuma.

É o caso de bulkhead que o desenho não faz, e a correção é barata: dois pools nomeados, um
por via, somando o mesmo teto. O que muda é que a fila passa a se formar do lado do trabalho
assíncrono em vez do lado do cliente. Não foi feito porque o cenário que o expõe não está
medido, e a campanha atual isola a via HTTP de propósito.

## Então por que rodar mais de uma task

Porque throughput não é o único motivo para ter réplicas:

- **Disponibilidade:** uma task por zona remove o nó como ponto único de falha.
- **Deploy:** o canário de `deploy.md` precisa de duas versões atendendo ao mesmo tempo.
- **Absorção de pico:** o cenário de surto (`load/results.md`) mostra a fila do pool subindo
  para 180 e voltando a zero sem erro; mais tasks distribuem essa fila.

O conflito é de orçamento, não de curva: **a disponibilidade quer muitas instâncias, e cada
instância leva o seu pool inteiro para dentro do `max_connections` do banco.** Resolver esse
conflito é o trabalho de projeto que a topologia atual não faz, e ele fica mais apertado, não
menos, agora que se sabe que conexões saturadas trabalham em vez de disputar.

## O pooler, e qual problema ele resolve de verdade

A resposta é um pooler de conexões entre as tasks e o banco, RDS Proxy ou PgBouncer em
transaction mode. O modo por transação é o compatível com este serviço: as transações são
curtas, não há estado de sessão a preservar entre chamadas e nada depende de prepared
statement fixado a uma conexão.

O que ele resolve é exatamente o esgotamento de `max_connections`, e o valor aqui é
**desacoplar o número de instâncias do número de conexões**: vinte instâncias para
disponibilidade e deploy, com o total contra o banco mantido dentro do orçamento. Sem ele, o
número de instâncias fica preso ao orçamento de conexão, o que é uma restrição de
disponibilidade disfarçada de detalhe de configuração.

O custo é um salto de rede a mais no caminho de cada autorização e um componente a operar.
Contra os quase 300 ms de p99 que a fila do pool já produz sob carga saturada, é troca
barata.

## Dez vezes o volume

Cerca de 27 mil req/s. O caminho, em ordem:

1. **Vertical primeiro.** Uma classe de RDS maior sobe as duas coisas que decidem: mais
   memória eleva o `max_connections`, e mais CPU e I/O sustentam mais conexões saturadas
   fazendo trabalho útil. É o passo mais barato e o menos interessante, e tem fim.
2. **Réplica de leitura não ajuda.** A carga é de escrita. Uma réplica serviria uma consulta
   de saldo, que este serviço não expõe.
3. **Particionar.** É onde a resposta de verdade está.

## Cem vezes: particionamento por conta

A propriedade que torna este desenho particionável é a mesma que o torna correto: **a
invariante de saldo nunca-negativo é local a uma linha.** Uma autorização toca exatamente uma
conta, e nunca duas. Consequências:

- `account_id` é chave de partição natural;
- **não existe transação entre partições**, porque não existe operação que envolva duas
  contas. Não há saga, não há commit em duas fases, não há compensação;
- a idempotência acompanha a conta, já que o claim é escrito na mesma partição da mutação.

Com a ordem de grandeza que uma instância de banco sustenta, cem vezes o volume são da ordem
de dezenas de partições, não uma reescrita. É a diferença entre um sistema que escala por projeto e um
que escala por sorte, e vale dizer que a decisão que a comprou foi o update condicional
atômico sobre uma linha, tomada por correção e não por escala.

## O que não particiona: a conta quente

Particionar distribui contas diferentes. Não faz nada por **uma** conta quente, porque a
serialização é sobre a linha.

Isso não é hipótese: a campanha mediu. Concentrando o tráfego em 10 contas, o throughput cai
dos cerca de 2,7 mil req/s do regime para 1853, e o p99 sobe dos cerca de 90 ms para 222, com
0% de erro. É a contenção do lock de linha aparecendo como número. Uma conta quente de
verdade, num sistema real, é a conta de liquidação de um lojista grande, e ela não é um caso
de laboratório.

As saídas, em ordem de custo:

- **Netting em janela.** Agregar movimentos da mesma conta num intervalo curto e aplicar um
  update por janela em vez de um por transação. Troca latência por throughput e muda a
  semântica: a decisão deixa de ser síncrona por transação, o que só é aceitável se a conta
  quente for de crédito, nunca de débito sob invariante.
- **Escritor único por conta.** Uma fila por conta com um consumidor, serializando na
  aplicação em vez de no banco. Elimina a disputa de lock e transforma a contenção em
  enfileiramento ordenado, ao custo de um mecanismo de particionamento de filas, de
  redistribuição quando um consumidor cai, e da perda da simplicidade que o update condicional
  compra hoje.

Nenhuma das duas foi construída aqui, porque o formato de tráfego que as justifica é
justamente o que a medição mostra ainda caber: 1853 req/s numa conta concentrada, sem erro.
**O gatilho para trocar é a conta quente sozinha passar do que uma linha aguenta**, e o número
para observar já está medido.

## O `Retry-After` foi calibrado contra a falha errada

Quando o circuito abre, a resposta é 503 com `Retry-After: 5` (ADR-008). Cinco segundos é um
número calibrado contra `bin/chaos`, que para um contêiner Postgres local: a falha é
instantânea e a volta também.

Um failover Multi-AZ real não se parece com isso. Há detecção, promoção da standby e
propagação de DNS, e a janela é de dezenas de segundos a minutos, não de cinco. Um cliente
que respeita o header literalmente reenvia a cada cinco segundos durante toda a promoção, e
o que era um mecanismo de alívio vira uma fonte de carga contra um banco que está no pior
momento dele.

A postura continua correta: qualquer caso de banco inalcançável responde 503 e nunca uma
recusa fabricada, então nenhuma decisão é inventada. O que está errado é a **calibração**, e
o número certo não é adivinhável: ele sai de medir a janela real de failover da classe de
instância escolhida. Enquanto isso não for medido, o comportamento honesto é um
`Retry-After` maior, ou crescente entre tentativas, em vez de um fixo curto derivado de um
`docker stop`.

## O que fica de fora, e por quê

**Load shedding e limite adaptativo de concorrência.** Hoje não existe controle de admissão:
a única proteção sob carga é o circuit breaker, que é ligado ao banco, então uma rajada de
requisições bem formadas que não esgote o pool não encontra nenhuma decisão de recusa antes do
connector HTTP. A resposta certa é um limite de concorrência em voo devolvendo 503 antes de o
pool esgotar.

Não foi implementado porque é código no caminho de entrada que muda o comportamento
exatamente no cenário de surto que a campanha mediu a 0% de erro. Um limite mal calibrado
transforma um pico hoje absorvido em erro, que é o mesmo engano que um `connectionTimeout` de
500 ms teria cometido e que a medição já descartou uma vez. Entra quando houver uma campanha
para validá-lo, não antes.
