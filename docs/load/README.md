# Prova de carga

Campanha de carga do autorizador com k6: método, ambiente, cenários e como reproduzir. O
desafio pressiona volume alto, então a medição é entregável, não item opcional. As regras
que a mantêm honesta estão declaradas aqui, antes de qualquer número, e os resultados ficam
em [results.md](results.md).

## Princípios

- **Gerador e servidor em máquinas separadas.** Rodar o k6 e o SUT no mesmo host mede uma
  fantasia: o gerador rouba CPU do servidor e o tráfego nunca atravessa uma rede real. Os
  números só valem no par isolado descrito em [provisioning.md](provisioning.md).
- **n maior ou igual a 3 por cenário.** Uma corrida única esconde variância. Cada cenário
  roda ao menos três vezes e a tabela mostra a faixa, não um recorte favorável.
- **Caveats antes dos números.** As limitações do ambiente estão escritas abaixo antes de
  existir qualquer resultado, para que nenhuma delas seja racionalizada depois do fato.
- **Corrida quebrada falha alto.** Cada cenário afirma um teto de taxa de erro. Se o app
  cai ou a URL está errada, o k6 sai com código diferente de zero em vez de imprimir
  percentis arrumados e sem sentido.

## Ambiente

Dois nós na mesma zona de disponibilidade, grupos de segurança abertos apenas entre eles e
para SSH. O SUT é dimensionado modestamente de propósito, para que a saturação seja
alcançável e interessante dentro do orçamento da campanha.

| Papel | Roda | Provisão |
|---|---|---|
| Gerador | k6 | binário k6, nada mais |
| SUT | app (imagem do container) + Postgres + localstack | Docker e a stack do compose |

O passo a passo de subir os dois nós, dimensioná-los e derrubá-los está em
[provisioning.md](provisioning.md). O ambiente é declarado lá e aqui antes de a primeira
corrida acontecer.

## Caveats

Declarados de saída, não como ressalva ao pé da tabela:

- **Nó único de SUT.** Uma instância do app e um Postgres em container, não RDS Multi-AZ
  nem várias tasks atrás de um balanceador. Mede o teto de um nó, não a escala horizontal
  do desenho de deploy.
- **Postgres em container, não RDS.** Disco, rede e tunings gerenciados diferem de uma
  instância RDS. O número é do container nesta máquina, não de um banco gerenciado.
- **localstack ocioso durante a medição.** O caminho medido é só HTTP. A stack SQS existe
  no SUT apenas para semear as 100 mil contas antes da campanha; durante as corridas ela
  fica parada e não compete de forma relevante por recursos.
- **Fidelidade do emulador.** A topologia de filas roda contra o localstack, não contra o
  SQS gerenciado. Isso não toca os números HTTP, mas o consumo de conta não é exercido sob
  o serviço real. Detalhe em [../failure-modes.md](../failure-modes.md).
- **Saldos começam em zero.** As contas semeadas nascem com saldo zero, então uma fração
  dos débitos recusa por fundo insuficiente. Recusa é HTTP 200 e exercita o caminho de
  refutação sob trava, então conta como trabalho de autorização real, não como erro.

## Cenários

Os quatro scripts vivem em [scripts/](scripts/), ao lado dos resultados que produzem. Todos
disparam `POST /transactions/{transactionId}` com id novo por requisição, mistura de crédito
e débito e valores em reais inteiros, contra a amostra de contas semeadas.

| Cenário | Script | O que mede |
|---|---|---|
| Aquecimento | `warmup.js` | Leva a JVM ao estado compilado pelo JIT e enche o pool antes de medir. Números descartados. |
| Regime | `steady.js` | O número de referência: throughput e p50/p99 sob concorrência fixa e sustentada. |
| Pico | `spike.js` | Uma base calma, um salto súbito bem acima dela, e a volta. Absorção do surto e recuperação da latência. |
| Concentração em conta quente | `hot-account-skew.js` | Tráfego concentrado em poucas contas: a contenção de trava de linha do update atômico, mostrada em vez de escondida. |

A concentração em conta quente é o cenário que expõe honestamente o custo do update
condicional atômico. Sob carga uniforme sobre 100 mil contas, duas requisições quase nunca
tocam a mesma linha, então a serialização no lock de linha fica invisível. Concentrar o
tráfego em poucas contas força essa serialização e a torna mensurável.

## Como reproduzir

O trabalho se divide entre as duas máquinas: a extração da amostra roda no SUT, contra o
Postgres semeado; o k6 roda no gerador, contra a porta HTTP do SUT. Rodar o k6 no próprio
SUT mediria loopback e invalidaria os números, então os passos abaixo dizem onde cada um
acontece. O passo de subir a stack e drenar a semente está em [provisioning.md](provisioning.md).

1. **No SUT, extrair a amostra de contas** para `scripts/accounts.json`, depois de a
   semente drenar:

   ```sh
   cd docs/load/scripts
   ./extract-accounts.sh
   ```

   Variáveis de ambiente ajustam origem e tamanho: `PGHOST`, `PGPORT`, `SAMPLE_SIZE`.

2. **Levar `accounts.json` para o gerador**, em `docs/load/scripts/`, para que o k6 o leia.
   Os grupos de segurança abrem só a porta HTTP entre as máquinas, então a cópia passa por
   SSH, não por acesso direto ao Postgres a partir do gerador.

3. **No gerador, aquecer** antes de medir (números descartados):

   ```sh
   k6 run -e BASE_URL=http://<sut>:8080 warmup.js
   ```

4. **No gerador, medir** cada cenário ao menos três vezes, guardando a saída JSON de cada
   corrida:

   ```sh
   k6 run -e BASE_URL=http://<sut>:8080 --summary-export steady-run1.json steady.js
   k6 run -e BASE_URL=http://<sut>:8080 --summary-export spike-run1.json  spike.js
   k6 run -e BASE_URL=http://<sut>:8080 --summary-export skew-run1.json    hot-account-skew.js
   ```

   Parâmetros por corrida: `VUS`, `DURATION`, `SPIKE_VUS`, `BASE_VUS`, `HOT_ACCOUNTS`. Os
   valores finais de tuning saem da medição, não de palpite, e são registrados em
   [results.md](results.md).

## Gráfico de saturação do pool

Sob virtual threads, o pool de conexões, não uma contagem de threads, é o teto de
concorrência: cada requisição que precisa do banco pega uma conexão do HikariCP, e quando
todas estão em uso as demais esperam. Esse é o limite projetado, então a corrida de regime
o mostra em vez de escondê-lo.

Durante a corrida de regime, amostre os gauges do pool a partir do endpoint Prometheus do
SUT:

```sh
cd docs/load/scripts
PROM_URL=http://<sut>:8080/actuator/prometheus ./scrape-hikari.sh
```

Ele grava `hikari.csv` com `active`, `idle`, `pending` e `max` por segundo. O gráfico de
`active` contra `max`, com `pending` subindo quando o pool enche, é a evidência do teto
sendo alcançado. O CSV e o PNG do gráfico ficam junto dos resultados em
[results.md](results.md).

## Medição de disco

Um pool saturado e um disco saturado produzem a mesma curva de fila, então afirmar que o
teto é o pool exige medir o armazenamento na mesma corrida em vez de deduzi-lo. Na mesma
janela do regime, amostre o disco do SUT:

```sh
cd docs/load/scripts
DEV=nvme0n1 ./scrape-iostat.sh
```

Ele grava `iostat.csv` com escritas por segundo, latência de escrita, profundidade de fila
e `%util`, uma linha por segundo. `DEV` aponta para o dispositivo raiz do SUT, e `INTERVAL`
muda o passo da amostragem. A latência de escrita é o número que decide: se ela permanece
baixa enquanto a fila do pool cresce, o disco não é a parede. O `%util` sozinho não decide
nada em SSD, onde ele indica apenas que havia I/O em voo.
