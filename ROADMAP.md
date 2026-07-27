# Roadmap

Direção do projeto: o que existe, o que vem a seguir e o que fica fora de escopo.

## Feito

- **Estrutura do repositório:** gates determinísticos (gitleaks, gate de prosa, CI),
  spec de convenções (`AGENTS.md`), docker-compose com Postgres, localstack SQS,
  topologia de filas com dead-letter queue e o gerador de 100 mil mensagens.
- **Fundações:** esquema do livro-razão versionado com Flyway (contas, reservas de
  idempotência, transações), dinheiro em centavos inteiros e a suíte ArchUnit que
  fixa a direção das dependências da arquitetura hexagonal.
- **Criação de contas via SQS:** consumer idempotente com ack por mensagem,
  classificação de veneno contra falha transitória, redrive para a dead-letter queue,
  detecção de duplicata divergente e desligamento que espera as mensagens em voo.
- **Autorização:** `POST /transactions/{transactionId}` com crédito e débito sob a
  invariante de saldo nunca-negativo, garantida por update condicional atômico;
  idempotência reservada antes de qualquer mutação de saldo; todos os corner cases
  testados, incluindo dois débitos concorrentes na mesma conta.
- **Production-ready:** espera com full jitter antes de nova tentativa no consumer,
  métricas em endpoint Prometheus, logs JSON com correlação, grupos de health separando
  banco e fila, imagem conteinerizada e smoke de ponta a ponta.
- **Narrativa de operação:** OpenAPI e coleção de requisições, `docs/failure-modes.md`
  por componente, diagrama de deploy em cloud pública e proposta de pipeline canário.
- **Prova de carga:** campanha k6 com gerador e SUT em máquinas isoladas, três corridas
  por cenário. Regime, pico e concentração em conta quente medidos em `docs/load/`, com a
  curva de saturação do pool e o tamanho do pool confirmado por varredura.
- **Revisão às cegas:** um par de olhos que não escreveu o código percorreu o repositório
  a partir de um clone limpo, e a janela de correção que ele apontou foi aplicada.

## Com mais tempo

Direções que o desenho atual já comporta e que um horizonte maior justificaria:

- **Outbox para efeitos colaterais exactly-once:** hoje a decisão é durável no banco, mas
  uma notificação ou publicação de evento a jusante seria at-least-once. Uma tabela de
  outbox escrita na mesma transação da autorização, drenada por um relay, fecharia isso.
- **Testes de contrato:** o esquema de requisição é uma suposição derivada da resposta
  exigida. Um teste de contrato contra o produtor real da fila e contra os consumidores da
  API travaria o formato antes de uma quebra chegar a produção.
- **Postura multi-região:** o serviço é sem estado, então o que decide a estratégia é o
  banco. Ativo-passivo com réplica promovível é o passo natural, e vale nomear o que ele
  custa: replicação entre regiões é assíncrona, então promover uma réplica aceita perder as
  decisões que ainda não haviam replicado. É exatamente o risco que a escolha de Multi-AZ
  síncrono dentro da região evita, e levá-lo para o desenho multi-região é uma decisão de
  RPO, não um detalhe de topologia. Ativo-ativo exigiria repensar a serialização do saldo,
  que hoje é local ao Postgres.

### Capacidade e escala

O desenho de frota está em [`docs/scale.md`](docs/scale.md); o que segue é o que ele aponta
e não foi construído, na ordem em que a medição justifica.

- **Pooler de conexões entre as instâncias e o banco:** é o próximo teto real, não uma
  otimização. A varredura em `docs/load/` mostra o throughput regredindo acima de 40
  conexões concorrentes, e cada instância abre 20, então a partir da quarta o serviço fica
  mais lento quanto mais instâncias tem. Um pooler em transaction mode desacopla número de
  instâncias de número de conexões, que é o que permite ter disponibilidade e throughput ao
  mesmo tempo em vez de escolher entre os dois.
- **Dois pools, um por via:** a autorização e o consumer SQS dividem o mesmo pool hoje, então
  um redrive grande consome conexão de quem tem cliente esperando. Separar move a fila para
  o lado do trabalho assíncrono, que é onde ela deve ficar.
- **Controle de admissão:** um limite de concorrência em voo devolvendo 503 antes de o pool
  esgotar. Não foi implementado porque muda o comportamento no cenário de surto que a
  campanha mede a 0% de erro, e um limite mal calibrado transforma em erro um pico hoje
  absorvido. Entra junto de uma campanha que o valide, não antes.
- **Particionamento por conta:** a invariante de saldo é local a uma linha, então
  `account_id` é chave de partição natural e não existe transação entre partições. É o
  caminho para uma ordem de grandeza acima do teto de um banco, e a decisão que o comprou
  foi tomada por correção, não por escala.
- **Conta quente:** particionar não ajuda uma única conta concentrada, e a campanha mede a
  queda. As saídas são netting em janela ou um escritor único por conta; o gatilho para
  decidir é a conta quente sozinha passar do que uma linha aguenta.
- **`Retry-After` calibrado contra um failover real:** o valor atual foi derivado de derrubar
  um contêiner, que falha instantâneo. A janela de uma promoção Multi-AZ é muito maior, e um
  cliente que respeita o header reenviaria durante toda ela.
- **Teto de ingestão da criação de contas, medido, e uma alavanca para movê-lo:** a campanha
  de carga isola a via HTTP de propósito, então o consumo da fila não tem número nenhum: nem
  taxa de drenagem, nem teto. Medir vem primeiro. Depois existem duas alavancas, e elas não
  são equivalentes. `DeleteMessageBatch` apaga até dez mensagens por chamada, contra a chamada
  por mensagem de hoje, o que corta uma ordem de grandeza em requisições ao SQS, em latência
  acumulada e em custo por mensagem; o preço é que o ack deixa de ser individual e passa a
  exigir tratamento de sucesso parcial, que é justamente a simplicidade que o ADR-005 escolheu
  ao decidir por ack por mensagem, então trocar exige revisitar aquela decisão e não apenas
  chamar outra API. A segunda alavanca é separar o consumer num deployable próprio, com
  orçamento de conexão próprio, o que resolve ao mesmo tempo o bulkhead entre as duas vias e
  devolve uma escala independente da via HTTP, hoje inexistente porque o consumer roda dentro
  da mesma instância e disputa o mesmo pool.
- **Ordem do livro-razão atribuída pelo banco:** o `timestamp` gravado é
  `clock.instant()` da instância que atendeu, escolhido para tornar os testes determinísticos.
  Com uma instância isso é irrepreensível; com várias, duas transações na mesma conta podem
  ficar gravadas fora da ordem real de commit, dentro do desvio de NTP entre as máquinas.
  Nada no sistema hoje depende dessa ordem, então não é defeito: nenhuma consulta ordena por
  ela e a invariante de saldo é garantida pelo `UPDATE` condicional, não por tempo. Vira
  defeito no dia em que alguém precisar reconstruir a sequência de movimentos de uma conta,
  que é exatamente o que um extrato faz. A saída é uma sequência monotônica do banco ou
  `clock_timestamp()` como ordem autoritativa, mantendo o relógio injetado só para o eco na
  resposta.
- **Calibração do circuit breaker medida, não arbitrada:** a janela é de 20 chamadas com
  mínimo de 10 e limiar de 50%. No throughput medido, 20 chamadas são cerca de sete
  milissegundos de tráfego, então um soluço muito curto basta para abrir o circuito e a
  instância passa a recusar pelos segundos seguintes. O ADR-008 argumenta a direção do falso
  negativo, reconhecer a dependência morta cedo, e não pesa a do falso positivo. Uma janela
  por tempo, com mínimo de chamadas proporcional à taxa esperada, faria o limiar ser medido
  sobre amostra com significado estatístico. O que falta antes de trocar é a medição: a
  campanha de carga rodou antes de o breaker existir, então não há taxa de abertura falsa sob
  carga para comparar, e mudar calibração de resiliência sem número é o erro que essa
  calibração deveria evitar.

## Fora de escopo

- Extrato bancário, consulta de saldo como endpoint dedicado, múltiplas moedas,
  matching/settlement: o serviço é um autorizador, não um core bancário completo.
