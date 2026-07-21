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

## Por que duas casas decimais, e não mais

Duas casas não é uma simplificação do desafio: é a **unidade mínima do BRL**, e este
serviço é uma **fronteira de lançamento**, não um motor de cálculo.

Valores com precisão sub-centavo existem de verdade num banco: a taxa de câmbio tem
seis ou mais casas, o rendimento diário de um CDB acumula fração de centavo. Mas eles
vivem em quem *calcula*, não em quem *lança*. O motor de câmbio multiplica pela taxa
cheia e pede a autorização do débito já arredondado ao centavo; o rendimento acumula
num livro de provisionamento e só toca a conta corrente como um crédito inteiro. É por
isso que o campo de valor do ISO 8583 é um inteiro em unidades monetárias mínimas e que
o Pix restringe o valor a duas casas em BRL: na hora do lançamento, o número já está
arredondado por quem tinha contexto para arredondá-lo.

Daí a recusa, em vez do arredondamento:

- **Não há resultado representável.** O saldo é `BIGINT` em centavos. Debitar meio
  centavo não tem resposta certa, e qualquer valor postado seria inventado aqui.
- **Arredondar aqui esconde o arredondamento.** O arredondamento de dinheiro acontece
  uma vez, em um lugar capaz de registrar a diferença; sistemas que legitimamente
  arredondam têm uma conta de arredondamento para receber o resto. Um autorizador não
  tem. Se ele recebesse `54.321` e postasse `54,32`, o décimo de centavo sumiria sem
  lançamento e os dois sistemas passariam a discordar sobre o que aconteceu: uma
  quebra de conciliação sem origem rastreável.
- **Sub-centavo chegando aqui é defeito de quem chamou.** Recusar devolve o erro a
  quem tem contexto para corrigi-lo; aceitar o absorve em silêncio.

Aceitar sub-centavo de verdade não seria alargar a escala: exigiria política de
arredondamento no contrato (quem arredonda, em que direção), resposta devolvendo valor
pedido e valor postado separadamente, e uma conta de arredondamento recebendo o resto.
É um desenho inteiro, não um parâmetro, e nenhuma das três peças tem requisito aqui.

O que a constante `SCALE = 2` de fato esconde é **outra** generalização: a unidade
mínima é uma propriedade da moeda, não do sistema (JPY tem zero casas, KWD tem três).
Um autorizador multimoeda troca a constante por uma escala derivada da moeda. É o
mesmo movimento descrito abaixo para o campo de moeda, e continua fora de escopo pelo
mesmo motivo.

## Consequências

- Aritmética de saldo é aritmética de inteiros: exata, comparável com `==`, e
  diretamente expressável em SQL, que é o que permite a atualização condicional
  atômica que garante a invariante de saldo não-negativo.
- O limite superior passa a ser o de `BIGINT` (cerca de 92 quatrilhões de centavos).
  Não é um limite teórico: um crédito que estoure esse teto é recusado explicitamente,
  em vez de virar saldo negativo por *overflow* silencioso.
- Valor com mais de duas casas decimais **significativas** na entrada é **recusado**,
  não arredondado. Arredondar dinheiro em silêncio absorve um erro de contrato do
  chamador dentro do livro-razão. Zeros à direita não contam: `10.5000` é o mesmo
  dinheiro que `10.50` e é aceito, porque escala de serialização não é precisão.
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
