# ADR-009: Orquestrador de containers e o dimensionamento da frota

Status: aceito

## Contexto

`docs/deploy.md` desenha a topologia sobre ECS Fargate e descreve o orquestrador como o que
"reconcilia a contagem desejada de tasks e substitui a que morre". Isso é verdade e não
decide nada: descreve o que qualquer orquestrador faz, inclusive o Kubernetes. A escolha
estava aplicada em todo o documento e argumentada em lugar nenhum, e o mesmo valia para o
compute type, nomeado sem tamanho, sem contagem e sem política de crescimento.

A decisão é menos sobre qual produto e mais sobre uma restrição que só apareceu quando a
campanha de carga foi lida para uma frota, e não para uma instância. A varredura de pool em
`docs/load/results.md` mediu throughput contra número de conexões: 1852 req/s com 10, 2667
com 20, 2769 com 40 e **2217 com 80**. A curva tem joelho entre 20 e 40 e regride depois.

Essa curva não é da aplicação, é do banco. O Postgres conta conexões, não de qual task elas
vieram, então o eixo real é o total de conexões concorrentes, e com `DB_POOL_SIZE=20` por
task isso significa que **duas tasks já colocam o banco no joelho e quatro entregam menos que
uma**. Escalar a aplicação horizontalmente não escala o sistema.

Ao mesmo tempo, mais de uma task continua sendo necessária: por zona de disponibilidade, para
o canário de `docs/deploy.md` ter duas versões atendendo ao mesmo tempo, e para distribuir a
fila do pool que o cenário de surto mostra chegando a 180. A disponibilidade quer muitas
tasks e o banco quer poucas conexões, e é esse conflito que o orquestrador precisa acomodar.
`docs/scale.md` desenvolve a aritmética.

## Decisão

**ECS Fargate, com o número de tasks governado pelo orçamento de conexão e não pela CPU.**

A escolha do orquestrador segue o tamanho do problema. O que o Kubernetes oferece a mais é
valor para uma frota de serviços heterogêneos: agendamento fino, bin-packing de cargas
distintas no mesmo nó, um ecossistema de operators, políticas por namespace. Nada disso é
exercido por um único serviço sem estado com duas dependências gerenciadas, e o custo é um
control plane que alguém opera. Fargate remove também a camada de nós, que é exatamente a
camada que este serviço não tem o que fazer com.

O que sustenta a decisão não é a preferência, é a **reversibilidade**, e ela é verificável
neste repositório:

- os grupos de health já existentes mapeiam um a um em probes: `readiness`, que segue o banco,
  vira `readinessProbe`; `liveness`, que não tem dependência, vira `livenessProbe`, e a
  separação entre as duas existe pelo mesmo motivo nos dois mundos, não reiniciar o processo
  porque a dependência caiu;
- a contagem desejada de tasks vira `replicas`;
- os pesos do canário viram o mesmo rollout em degraus;
- a configuração entra por ambiente (12-factor), então não há nada a reescrever entre um
  ConfigMap e a definição de task.

Nenhuma linha de código conhece o orquestrador. A decisão é de operação, não de arquitetura,
e é isso que a torna barata de mudar.

**Dimensionamento**, que é a parte que faltava:

- **Tamanho da task:** o teto de concorrência é o pool de conexões, não a CPU. A campanha
  sustenta ~2,7 mil req/s por task com 20 conexões, e o `iostat` da mesma campanha mostra que
  o disco não é a parede. A task é dimensionada para caber esse pool com folga de heap, e
  crescer CPU sem crescer o orçamento de conexão não compra throughput.
- **Contagem:** o piso é a disponibilidade, uma task por zona, não o throughput. O teto é o
  orçamento de conexão contra o banco, e não a demanda.
- **Autoscaling:** por utilização do pool, não por CPU. Uma métrica de CPU manda subir tasks
  exatamente no momento em que subir tasks piora o sistema, porque a fila está no pool e não
  no processador. Enquanto não houver pooler, a política é de teto fixo.

## Consequências

- O número de tasks deixa de ser um parâmetro livre e passa a ser derivado de uma conta que
  está escrita. Quem for aumentar a frota precisa aumentar o orçamento de conexão primeiro, e
  isso é uma restrição desconfortável de propósito.
- Autoscaling por CPU, que é o default de qualquer plataforma, fica explicitamente proibido
  aqui. É contraintuitivo o bastante para precisar estar escrito.
- Enquanto não existir um pooler entre as tasks e o banco, disponibilidade e throughput
  competem: cada task a mais por redundância consome do mesmo orçamento. `docs/scale.md`
  registra o pooler como o passo que desfaz esse conflito, e ele é a próxima peça de
  infraestrutura, não uma otimização.
- A migração para Kubernetes, se o contexto organizacional pedir, é uma tradução de
  manifesto, não uma mudança de desenho. O que não muda é o orçamento de conexão, que
  continuaria governando as réplicas do mesmo jeito.

## Alternativas consideradas

**Kubernetes gerenciado (EKS).** Recusado para este escopo, não em geral. Numa organização
que já opera Kubernetes a resposta correta é Kubernetes, porque o custo marginal de mais um
Deployment é próximo de zero e o custo de manter uma ilha de ECS ao lado não é. O que pesou
aqui é que o serviço é um só, sem estado, com duas dependências gerenciadas, e não exerce
nenhuma das capacidades pelas quais se paga o control plane. Adotá-lo por padrão seria
escolher a ferramenta pela expectativa de quem lê, e não pelo problema.

**Instâncias EC2 sob um auto scaling group, sem orquestrador.** Recusado. Substituir uma
instância doente e reconciliar contagem desejada é exatamente o trabalho que se estaria
reimplementando, e a topologia perderia a substituição por task que o canário usa.

**Função serverless por requisição.** Recusado, e o motivo é o mesmo orçamento de conexão
acima: um modelo que cria uma execução por requisição multiplica conexões contra um banco
cuja curva já regride em 80. Seria a pior escolha possível para esta restrição específica,
salvo introduzindo um pooler como pré-requisito obrigatório em vez de próximo passo.

**Dimensionar por CPU, como default.** Recusado pela medição: a fila está no pool, e a
varredura mostra throughput caindo quando o total de conexões passa do joelho. Uma política
de CPU escalaria para dentro do problema.
