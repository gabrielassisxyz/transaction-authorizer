# ADR-002: Controle de concorrência do saldo

Status: aceito

## Contexto

A invariante central do serviço é que o saldo nunca fica negativo, nem sob dois débitos
concorrentes sobre a mesma conta. A abordagem ingênua, ler o saldo, conferir em memória
e gravar o novo valor, tem uma janela entre a leitura e a escrita em que outra
transação lê o mesmo saldo antigo. As duas conferem que há fundo, as duas debitam, e o
saldo termina negativo. Nenhuma quantidade de teste de unidade sobre código de domínio
puro pega isso: a corrida só existe contra um banco real, com escritas reais em
paralelo.

## Decisão

A invariante vive em uma única instrução SQL, não em Kotlin. O débito é uma atualização
condicional atômica:

```sql
UPDATE accounts SET balance_cents = balance_cents - :amount
WHERE id = :id AND status = 'ENABLED' AND balance_cents >= :amount
RETURNING balance_cents
```

Não há leitura seguida de escrita: a condição `balance_cents >= :amount` e o decremento
acontecem na mesma instrução, sob o lock de linha que o Postgres adquire para o
`UPDATE`. Em READ COMMITTED, uma segunda transação que dispute a mesma linha bloqueia até
a primeira confirmar, e então reavalia o `WHERE` sobre o valor já atualizado. Se o fundo
acabou, casa zero linhas e o débito é recusado. Exatamente um dos dois débitos
concorrentes vence, e o saldo nunca cruza zero. O crédito é simétrico, com uma guarda de
overflow (`balance_cents <= :maxCents - :amount`) no lugar da guarda de fundo, para que
um crédito que estouraria o teto do `BIGINT` seja recusado em vez de virar saldo
negativo por transbordo.

Quando a atualização condicional casa zero linhas, entra o caminho lento, e só ele
adquire lock explícito:

```sql
SELECT status, balance_cents FROM accounts WHERE id = :id FOR UPDATE
```

O caminho lento roda apenas na recusa ou na anomalia, onde vale pagar o lock para que a
resposta seja consistente com a decisão. Com a linha travada, a recusa por fundo
insuficiente reporta o saldo travado como `balance_after`, e assim uma resposta FAILED
nunca carrega um saldo que visivelmente cobriria o débito. Se, entre a atualização
condicional e o lock, um crédito concorrente entrou e o débito agora cabe, ele é
aplicado sob o lock e a transação é aprovada.

A ordem de lock é sempre a mesma, primeiro a tabela de claims da idempotência (ADR-004),
depois `accounts`, então o fluxo não tem como formar um ciclo de espera. Todo o trabalho
roda em READ COMMITTED, que é o padrão do Postgres: não é preciso SERIALIZABLE, porque a
atualização condicional já serializa quem disputa a linha.

## Consequências

- A invariante é uma propriedade do banco, não do código de aplicação, e sobrevive a
  qualquer reinício, a múltiplas instâncias do serviço e a qualquer intercalação de
  threads. A suíte de concorrência dispara dezenas de débitos em paralelo e afirma o
  saldo final exato e a contagem exata de aprovações.
- Não há retry em laço nem SERIALIZABLE, logo não há falha de serialização para tratar
  no caminho de sucesso, que é uma única instrução.
- O caminho lento com `FOR UPDATE` custa um lock, mas só é alcançado na recusa ou na rara
  corrida em que um crédito salva um débito, nunca na aprovação comum.
- A lógica de saldo mora no adaptador de persistência como SQL nativo. A condição não é
  expressável como JPQL sobre colunas reais, e uma atualização em massa do JPA passa por
  fora do contexto de persistência, então nenhuma `AccountEntity` gerenciada é mantida
  para a mesma linha dentro desta transação.

## Alternativas consideradas

- **Ler, conferir, gravar na aplicação**: a abordagem que a invariante existe para
  proibir. A janela entre leitura e escrita é exatamente onde o saldo fica negativo.
- **`SELECT ... FOR UPDATE` sempre, no início**: correto, mas paga o lock em toda
  autorização, inclusive na aprovação comum, que é o caminho quente. A atualização
  condicional aprova sem lock explícito e reserva o `FOR UPDATE` para a recusa.
- **Isolamento SERIALIZABLE**: também garante a invariante, mas troca a corrida por
  falhas de serialização que exigem laço de retry, mais lentas e mais complexas do que a
  atualização condicional para o mesmo resultado.
- **Trava otimista com coluna de versão**: um `version` incrementado a cada escrita
  detecta a escrita concorrente, mas a resolve com retry na aplicação, reintroduzindo o
  laço que a atualização condicional dispensa.
