# Coleção de requisições

Dois formatos da mesma coleção, cobrindo todo comportamento documentado da autorização:
crédito aprovado, débito aprovado, débito recusado por fundo insuficiente, recusa por
conta desabilitada, conta inexistente com 404, requisição malformada com 400, moeda não
suportada com 422 e replay idempotente.

- `transactions.http`: formato nativo de IDE (IntelliJ HTTP Client, extensão REST Client
  do VS Code). Abra e dispare requisição por requisição.
- `transaction-authorizer.postman_collection.json`: importe no Postman ou rode com o
  Newman.

Ambos usam variáveis, e a única que precisa de ajuste é a `accountId` (o `account_id` no
corpo é preenchido por ela). Suba o sistema conforme o README na raiz, e então:

## Obter um account_id semeado

As contas entram pela fila e são criadas pelo consumer, então basta ler uma do banco
depois que a drenagem começou:

```bash
docker compose exec postgres \
  psql -U authorizer -d transaction_authorizer -tAc 'SELECT id FROM accounts LIMIT 1'
```

Toda conta semeada nasce com saldo zero, então o primeiro crédito da coleção é o que
levanta o saldo para os débitos seguintes.

## Rodar a coleção mais de uma vez

Os ids de transação são fixos no arquivo (`creditId`, `debitId` e os literais dos
demais cenários), e a identidade de idempotência é o `transactionId` sozinho, não o par
conta+id. Rodar a coleção de novo com os mesmos ids reenvia cada requisição contra a
decisão já armazenada em vez de mover saldo, o que também invalida os cenários
seguintes, que assumem o saldo do primeiro crédito. Para repetir a demonstração, troque
`creditId`, `debitId` e os UUIDs literais das demais requisições por valores novos antes
de disparar de novo.

## Desabilitar uma conta para o cenário de recusa

O ciclo de vida da conta não é exposto pela API: toda conta semeada é `ENABLED`. Para
exercer a recusa por conta desabilitada, mude o status direto no banco e aponte a
requisição para essa conta:

```bash
docker compose exec postgres psql -U authorizer -d transaction_authorizer \
  -c "UPDATE accounts SET status = 'DISABLED' WHERE id = '<id da conta>'"
```
