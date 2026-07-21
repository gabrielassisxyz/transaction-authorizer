# ADR-003: Dinheiro em centavos inteiros e moeda única

Status: aceito

## Contexto

O serviço mantém saldo de conta e valores de transação. Representar dinheiro em ponto
flutuante binário (`Double`, `Float`) é um erro clássico: `0.1 + 0.2` não é `0.3`, e
um autorizador que erra centavos por arredondamento perde a única propriedade que
justifica sua existência. `BigDecimal` resolve a precisão, mas carrega escala variável
e comparação por `equals` sensível à escala (`10.5` ≠ `10.50`), o que transforma cada
comparação de saldo em uma armadilha.

## Decisão

O saldo e os valores trafegam e são armazenados como **centavos inteiros** (`Long`,
coluna `BIGINT`). O tipo `Money` do domínio encapsula esse inteiro. `BigDecimal`
aparece somente na borda HTTP, para converter o decimal do JSON em centavos e de volta;
nenhuma aritmética de domínio o usa.

A moeda é **BRL apenas**. `Money` não carrega um campo de moeda: a constante
`Money.CURRENCY` documenta a unidade, a coluna `currency` guarda o valor com um
`CHECK (currency = 'BRL')`, e a borda HTTP recusa qualquer outra moeda.

## Consequências

- Aritmética de saldo é aritmética de inteiros: exata, comparável com `==`, e
  diretamente expressável em SQL, que é o que permite a atualização condicional
  atômica que garante a invariante de saldo não-negativo.
- O limite superior passa a ser o de `BIGINT` (cerca de 92 quatrilhões de centavos).
  Não é um limite teórico: um crédito que estoure esse teto é recusado explicitamente,
  em vez de virar saldo negativo por *overflow* silencioso.
- Valor com mais de duas casas decimais na entrada é **recusado**, não arredondado.
  Arredondar dinheiro em silêncio absorve um erro de contrato do chamador dentro do
  livro-razão.
- Multimoeda fica fora de escopo. Adicioná-la depois significa acrescentar o campo de
  moeda a `Money` e às queries: mudança real, mas contida, e sem custo nenhum
  enquanto o serviço opera em uma moeda só.

## Alternativas consideradas

- **`BigDecimal` no domínio**: correto quanto à precisão, mas a escala variável exige
  normalização em toda comparação, e o mapeamento para a atualização condicional em
  SQL fica mais frágil. O ganho seria suportar moedas com número de casas diferente de
  duas, um requisito que não existe.
- **Campo de moeda em `Money` desde já**: seria a modelagem "correta" de um sistema
  multimoeda, mas o serviço não é um. Um campo que só pode assumir um valor não
  documenta uma capacidade, apenas obriga toda comparação a verificá-lo.
