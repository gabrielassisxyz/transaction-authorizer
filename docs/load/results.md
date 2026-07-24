# Resultados

Números da campanha de carga, um bloco por cenário, cada um com ao menos três corridas no
par isolado. O método, o ambiente e os caveats que enquadram estes números estão no
[README](README.md); leia-os antes das tabelas.

> **Estado:** as tabelas abaixo aguardam a execução no par isolado descrito em
> [provisioning.md](provisioning.md). O harness k6 e a coleta de saturação estão prontos e
> validados; falta a corrida nas duas máquinas, que preenche os campos marcados como
> pendentes. Nenhum número é inventado: um campo sem medição fica pendente, não estimado.

## Máquinas

Preenchido a partir do provisionamento, antes da primeira corrida:

| Papel | Tipo de instância | vCPU | Memória | Zona |
|---|---|---|---|---|
| Gerador | pendente | pendente | pendente | pendente |
| SUT | pendente | pendente | pendente | pendente |

Configuração do SUT na campanha: `DB_POOL_SIZE` e `SQS_POLLERS` nos valores da tabela de
tuning abaixo.

## Regime

Concorrência fixa e sustentada, a via HTTP isolada. É o número de referência.

| Corrida | VUs | Throughput (req/s) | p50 (ms) | p99 (ms) | Taxa de erro |
|---|---|---|---|---|---|
| 1 | pendente | pendente | pendente | pendente | pendente |
| 2 | pendente | pendente | pendente | pendente | pendente |
| 3 | pendente | pendente | pendente | pendente | pendente |

## Pico

Base calma, salto súbito, volta. Percentis lidos à parte do regime, já que o pico mistura
dois regimes de propósito.

| Corrida | Base VUs | Pico VUs | Throughput no pico (req/s) | p99 no pico (ms) | Taxa de erro | Latência recuperou? |
|---|---|---|---|---|---|---|
| 1 | pendente | pendente | pendente | pendente | pendente | pendente |
| 2 | pendente | pendente | pendente | pendente | pendente | pendente |
| 3 | pendente | pendente | pendente | pendente | pendente | pendente |

## Concentração em conta quente

Mesma concorrência do regime, tráfego concentrado em poucas contas. Aqui a contenção de
trava de linha do update atômico aparece: p99 e throughput caem contra o regime, e essa
diferença é o custo honesto da serialização no saldo, não um defeito.

| Corrida | VUs | Contas quentes | Throughput (req/s) | p50 (ms) | p99 (ms) | Taxa de erro |
|---|---|---|---|---|---|---|
| 1 | pendente | pendente | pendente | pendente | pendente | pendente |
| 2 | pendente | pendente | pendente | pendente | pendente | pendente |
| 3 | pendente | pendente | pendente | pendente | pendente | pendente |

## Saturação do pool HikariCP

Gráfico de `active` contra `max`, com `pending` subindo quando o pool enche, durante a
corrida de regime. É a evidência do teto projetado: sob virtual threads o pool, não a
contagem de threads, limita a concorrência. Fonte de dados em `hikari.csv`, coletado por
`scripts/scrape-hikari.sh`; o PNG do gráfico entra aqui.

> Gráfico pendente da corrida no par isolado.

## Tuning medido

Os valores de partida do pool e dos pollers foram escolhidos por raciocínio, não por
medição, e ficam nestes defaults até a campanha os confirmar ou corrigir com evidência. Um
valor só muda acompanhado do número que justifica a mudança.

| Parâmetro | Partida | Final | Evidência |
|---|---|---|---|
| `DB_POOL_SIZE` (HikariCP) | 20 | pendente | pendente |
| `SQS_POLLERS` | 2 | pendente | pendente |

O tamanho do pool é o teto de concorrência da via HTTP, então seu valor final sai da
corrida de regime e da curva de saturação: o ponto em que subir o pool deixa de melhorar o
throughput e passa a só encher o banco. A contagem de pollers afeta a drenagem da semente,
não a via medida, então seu ajuste é secundário nesta campanha.
