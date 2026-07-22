# ADR-004: Idempotência da autorização

Status: aceito

## Contexto

O identificador da transação chega do cliente, na URL de `POST /transactions/{id}`. Um
cliente que não recebe a resposta, por timeout ou queda de conexão, reenvia a mesma
transação com o mesmo id. O serviço precisa mover o saldo uma única vez e devolver, no
reenvio, exatamente a decisão que tomou da primeira vez. Dois reenvios podem ainda
chegar concorrentes, competindo pelo mesmo id ao mesmo tempo.

## Decisão

A idempotência é ancorada em uma tabela dedicada, `transaction_claims`, cuja chave
primária é o id da transação. O primeiro passo de toda autorização nova é reivindicar o
id:

```sql
INSERT INTO transaction_claims (id, request_hash) VALUES (:id, :hash)
```

O claim é inserido antes de qualquer mexida no saldo, então a chave primária serializa
duplicatas logo na entrada, em vez de depender de um rollback para desfazer trabalho já
feito. Dois reenvios concorrentes disputam esse `INSERT`: o Postgres bloqueia o segundo
até o primeiro confirmar, e então o segundo recebe a violação de chave. Na violação, a
transação perdedora é revertida, e a linha de `transactions` que a vencedora gravou já
está confirmada e legível, então a perdedora relê essa linha e devolve a mesma decisão.
Não há laço de retry, e nenhum trabalho de saldo é desperdiçado.

Um reenvio que chega depois que a primeira decisão já foi gravada nem abre transação de
escrita: um `SELECT` na tabela `transactions`, fora de transação, encontra a decisão
armazenada e a reconstrói a partir das colunas persistidas (`result`, `balance_after`,
`created_at`). A resposta devolvida no reenvio é idêntica à primeira, byte a byte nos
campos do contrato.

O `request_hash` é um SHA-256 sobre a identidade da requisição (conta, tipo, valor em
centavos, moeda), calculado no serviço de aplicação e guardado com o claim. Ele separa um
reenvio genuíno, mesmo id e mesmo conteúdo, de um id reutilizado para uma requisição
diferente. No reenvio, se o hash recebido diverge do armazenado, a primeira decisão
prevalece e é devolvida, mas a divergência é registrada em log, porque um id reusado para
outro conteúdo é evidência de um defeito de quem chamou, não de um reenvio normal.

## Consequências

- Entrega repetida é inofensiva: o saldo se move uma vez, e todo reenvio, concorrente ou
  tardio, recebe a decisão original. A suíte de concorrência dispara o mesmo id em
  paralelo e afirma uma única linha em `transactions` e o saldo movido exatamente uma vez.
- Uma recusa também é idempotente. A linha de `transactions` é gravada para os dois
  desfechos, SUCCEEDED e FAILED, então o reenvio de uma transação recusada devolve a
  mesma recusa, sem reavaliar o saldo.
- Uma conta inexistente é o único desfecho que reverte o claim em vez de gravá-lo: sem a
  conta, não há linha de `transactions` a escrever, e deixar o claim confirmado
  bloquearia para sempre um id que passaria a valer assim que a conta fosse criada. O
  caminho de conta ausente reverte, o claim some, e um reenvio posterior volta a
  funcionar.
- A reconstrução da resposta a partir da linha armazenada não recupera o motivo da
  recusa, porque o contrato HTTP responde SUCCEEDED ou FAILED sem campo de motivo
  (ADR-006) e o motivo não é persistido. O motivo existe apenas no log do momento da
  decisão.
- Métrica de desfecho e de payload divergente fica para o marco de observabilidade; nesta
  fatia a divergência é registrada só em log.

## Alternativas consideradas

- **Idempotência só pela linha de `transactions`, sem tabela de claims**: a linha final
  só existe no fim da transação, depois do trabalho de saldo. Dois reenvios concorrentes
  fariam os dois o débito antes que qualquer linha existisse para bloquear o segundo. O
  claim inserido primeiro é o que serializa a duplicata antes de mover saldo.
- **Lock aplicacional por id (por exemplo um advisory lock)**: resolve a concorrência,
  mas adiciona um mecanismo de lock à parte para o que a chave primária do claim já faz,
  e não deixa rastro persistente do id reivindicado.
- **Deduplicação por chave só em memória ou em cache**: perde a idempotência num reinício
  ou entre instâncias, exatamente quando o reenvio por timeout é mais provável.
