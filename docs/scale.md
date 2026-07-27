# Capacidade e escala

Quantas instâncias este serviço comporta, o que limita esse número, e o que muda no desenho
quando o volume cresce dez e cem vezes. A campanha em [`load/`](load/) mede uma instância;
este documento é a leitura dela para uma frota.

## O teto não é a aplicação, é a concorrência do banco

A varredura de pool da campanha (`load/results.md`, seção de tuning) mediu o throughput de
uma instância variando só o tamanho do pool de conexões:

| Conexões | Throughput (req/s) | p99 (ms) |
|---|---|---|
| 10 | 1852 | 203 |
| 20 | 2667 | 88 |
| 40 | 2769 | 55 |
| 80 | 2217 | 46 |

A curva tem joelho entre 20 e 40, e **cai em 80**. Passado o joelho, mais conexões concorrem
por CPU, disco e locks do mesmo Postgres sem atender mais ninguém.

Isso é normalmente lido como "o pool está bem dimensionado", e é. A leitura que importa aqui
é outra: **a curva não é da aplicação, é do banco.** O Postgres enxerga conexões, não de qual
task elas vieram, então o eixo dessa tabela não é "pool por instância", é **total de conexões
concorrentes contra este banco**. E é isso que decide a frota.

## Aritmética da frota

Com `DB_POOL_SIZE=20`, cada task abre até 20 conexões. Contra a curva acima:

| Tasks | Conexões totais | Throughput esperado | O que acontece |
|---|---|---|---|
| 1 | 20 | ~2667 req/s | joelho da curva |
| 2 | 40 | ~2769 req/s | +4%, dentro da variância |
| 4 | 80 | ~2217 req/s | **abaixo de uma task só** |
| 8 | 160 | pior | contenção pura |

**Escalar a aplicação horizontalmente não escala o sistema.** Duas tasks já colocam o banco
no joelho; da quarta em diante o serviço fica mais lento quanto mais instâncias tem. É o
resultado contraintuitivo que a própria campanha já continha e que a topologia de deploy, ao
desenhar um balanceador sobre "múltiplas tasks", não endereça.

Uma ressalva de honestidade sobre esta tabela: a varredura variou o pool de **uma** instância.
Tratar 40 conexões vindas de duas tasks como equivalentes a 40 vindas de uma é uma suposição
razoável, porque o Postgres não distingue a origem, mas é suposição e não medição. Confirmá-la
exige uma corrida com duas instâncias, que a campanha atual não fez.

Antes de qualquer disso, existe um teto administrativo: o `max_connections` do Postgres, que
no RDS é função da memória da classe de instância. Ele importa menos do que parece, porque a
curva acima já degrada bem antes de qualquer limite administrativo razoável ser atingido. O
limite real chega primeiro que o limite configurado.

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

O conflito é direto: **a disponibilidade quer muitas tasks, o banco quer poucas conexões.**
Resolver esse conflito é o trabalho de projeto que a topologia atual não faz.

## O pooler, e qual problema ele resolve de verdade

A resposta é um pooler de conexões entre as tasks e o banco, RDS Proxy ou PgBouncer em
transaction mode. O modo por transação é o compatível com este serviço: as transações são
curtas, não há estado de sessão a preservar entre chamadas e nada depende de prepared
statement fixado a uma conexão.

O que ele resolve normalmente é esgotamento de `max_connections`. Aqui o valor é outro e é
maior: **ele desacopla o número de tasks do número de conexões.** Vinte tasks para
disponibilidade e deploy, com o pooler mantendo o total contra o banco dentro do joelho
medido. Sem ele, escolher entre disponibilidade e throughput; com ele, os dois.

O custo é um salto de rede a mais no caminho de cada autorização e um componente a operar.
Contra os 88 ms de p99 no joelho, é troca barata.

## Dez vezes o volume

Cerca de 27 mil req/s. O caminho, em ordem:

1. **Vertical primeiro.** Uma classe de RDS maior move o joelho para a direita: mais CPU e
   mais memória sustentam mais conexões concorrentes antes da curva virar. É o passo mais
   barato e o menos interessante, e tem fim.
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

Com o joelho medido em ~2700 req/s por instância de banco, cem vezes o volume são da ordem de
dez partições, não uma reescrita. É a diferença entre um sistema que escala por projeto e um
que escala por sorte, e vale dizer que a decisão que a comprou foi o update condicional
atômico sobre uma linha, tomada por correção e não por escala.

## O que não particiona: a conta quente

Particionar distribui contas diferentes. Não faz nada por **uma** conta quente, porque a
serialização é sobre a linha.

Isso não é hipótese: a campanha mediu. Concentrando o tráfego em 10 contas, o throughput cai
de 2667 para **1853 req/s** e o p99 sobe de 88 para 222 ms, com 0% de erro. É a contenção do
lock de linha aparecendo como número. Uma conta quente de verdade, num sistema real, é a conta
de liquidação de um lojista grande, e ela não é um caso de laboratório.

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
