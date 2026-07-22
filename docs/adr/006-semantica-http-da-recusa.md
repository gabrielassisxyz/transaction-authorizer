# ADR-006: Semântica HTTP da recusa

Status: aceito

## Contexto

`POST /transactions/{id}` pode terminar de várias formas: a transação é aprovada, é
recusada por fundo insuficiente ou conta desabilitada, a conta não existe, ou a
requisição está malformada. HTTP oferece códigos de status para tudo isso, e é tentador
mapear uma recusa de saldo para um erro 4xx. A questão é qual desses desfechos é um erro
do protocolo e qual é uma resposta de negócio bem-sucedida que por acaso diz não.

## Decisão

Uma recusa é um resultado de domínio, não um erro de transporte. Uma autorização
aprovada e uma recusada retornam ambas **200**, com o mesmo corpo, diferindo apenas no
campo `status`:

```json
{
  "transaction": { "id": "...", "type": "DEBIT", "amount": { "value": 10.50, "currency": "BRL" },
                   "status": "SUCCEEDED", "timestamp": "..." },
  "account":     { "id": "...", "balance": { "value": 5.00, "currency": "BRL" } }
}
```

O serviço processou a requisição, tomou uma decisão e a registrou. Que a decisão tenha
sido recusar o débito é o trabalho do autorizador funcionando, não uma falha. O campo
`status` carrega SUCCEEDED ou FAILED, e não há campo de motivo: o contrato não expõe por
que a recusa aconteceu, e por isso o motivo também não é persistido.

O que sai por um código de erro é o que impede o serviço de tomar uma decisão:

- **400** para requisição malformada: JSON inválido, campo ausente, id de transação que
  não é UUID, valor com mais de duas casas decimais significativas, valor não positivo.
- **404** para conta inexistente: não há sujeito sobre o qual decidir.
- **422** para requisição bem formada mas não processável: moeda diferente de BRL, ou
  valor fora da faixa representável do livro-razão.

Todos os desfechos de erro usam `ProblemDetail` (RFC 9457), servido como
`application/problem+json`, com o status e uma descrição do que impediu a decisão.

A separação entre 400 e 422 segue a distinção da própria especificação HTTP: 400 é
sintaxe que o servidor não consegue interpretar, 422 é sintaxe válida com semântica que
o servidor não aceita. Um valor com três casas decimais é uma violação de formato do
campo, logo 400. Uma moeda estrangeira é um valor perfeitamente formado que este serviço,
que opera só em BRL (ADR-003), não processa, logo 422.

## Consequências

- O cliente distingue "foi recusado" de "não deu para processar" pelo código de status,
  sem precisar interpretar corpo de erro no caminho comum. Uma recusa de saldo é 200 com
  `status: FAILED`, previsível e uniforme.
- A recusa é persistida como uma transação FAILED e é idempotente (ADR-004): reenviar uma
  transação recusada devolve a mesma recusa, também com 200.
- A conversão de `BigDecimal` para centavos, com toda a validação de escala, moeda e
  faixa, acontece na borda HTTP. O domínio nunca vê um valor inválido, e cada modo de
  falha da conversão vira o `ProblemDetail` correspondente.
- O esquema de requisição não veio de um contrato externo publicado: foi derivado do
  corpo de resposta exigido. Essa suposição está declarada na descrição do OpenAPI, não
  escondida.

## Alternativas consideradas

- **402 Payment Required para fundo insuficiente**: reaproveita um código reservado e
  historicamente sem semântica firme, e ainda trata uma decisão de negócio bem-sucedida
  como erro de protocolo. Um cliente passaria a tratar a recusa esperada como exceção.
- **409 Conflict para saldo insuficiente**: sugere um conflito de estado a resolver e
  repetir, mas a recusa é final e determinística, não um conflito que um novo envio
  resolve.
- **200 para tudo, inclusive conta inexistente e payload inválido**: apaga a diferença
  entre uma decisão tomada e uma requisição que impediu qualquer decisão, e obrigaria o
  cliente a inspecionar o corpo para saber se houve sequer processamento.
