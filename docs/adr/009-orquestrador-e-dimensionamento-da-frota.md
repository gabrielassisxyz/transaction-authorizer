# ADR-009: Orquestrador de containers e o dimensionamento da frota

Status: aceito

## Contexto

`docs/deploy.md` desenha a topologia sobre ECS Fargate e descreve o orquestrador como o que
"reconcilia a contagem desejada de tasks e substitui a que morre". Isso é verdade e não
decide nada: descreve o que qualquer orquestrador faz, inclusive o Kubernetes. A escolha
estava aplicada em todo o documento e argumentada em lugar nenhum, e o mesmo valia para o
compute type, nomeado sem tamanho, sem contagem e sem política de crescimento.

A decisão é menos sobre qual produto e mais sobre o que de fato limita a contagem de tasks.
A varredura refeita em `docs/load/results.md` mediu o pool com a carga oferecida constante e
com a saturação registrada: a 98% de ocupação, quadruplicar as conexões de 20 para 80 entrega
30% mais vazão e uma cauda 45% menor, com o host ainda em 35% de CPU ociosa. Conexões
saturadas trabalham; não disputam.

O que limita a frota, portanto, é um orçamento finito. O `max_connections`
do Postgres é finito e compartilhado, é função da memória da classe de instância do RDS, e
cada task leva o seu pool inteiro para dentro dele. Com `DB_POOL_SIZE=20`, N tasks consomem
20 × N desse orçamento, e a topologia desenha um balanceador sobre "múltiplas tasks" sem
nunca dizer quantas cabem nem nomear a classe que decide o teto.

Ao mesmo tempo, mais de uma task continua sendo necessária: por zona de disponibilidade, para
o canário de `docs/deploy.md` ter duas versões atendendo ao mesmo tempo, e para distribuir a
fila do pool que o cenário de surto mostra chegando a 180. A disponibilidade quer muitas
tasks e o orçamento de conexão do banco é finito, e é esse conflito que o orquestrador precisa
acomodar.
`docs/scale.md` desenvolve a aritmética.

## Decisão

**ECS Fargate, com o número de tasks governado pelo orçamento de conexão do banco, e não
pela CPU.**

A escolha do orquestrador segue o tamanho do problema. O que o Kubernetes oferece a mais é
valor para uma frota de serviços heterogêneos: agendamento fino, bin-packing de cargas
distintas no mesmo nó, um ecossistema de operators, políticas por namespace. Nada disso é
exercido por um único serviço sem estado com duas dependências gerenciadas, e o custo é um
control plane que alguém opera. Fargate remove também a camada de nós, para a qual este
serviço não tem uso.

O que sustenta a decisão é a reversibilidade, e ela é verificável neste repositório:

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
- **Autoscaling:** por utilização do pool, não por CPU. A medição é direta: com o pool a 98%
  de ocupação e a fila em 134 requisições, o host ficava com 48% de CPU ociosa. Uma política
  de CPU não reage a nada disso, porque a espera está no pool e não no processador, então ela
  subdimensiona exatamente quando o serviço está no limite. E o teto continua sendo o
  orçamento de conexão: enquanto não houver pooler, subir tasks consome esse orçamento em vez
  de criar capacidade, então a política é de teto fixo declarado.

## Consequências

- O número de tasks deixa de ser um parâmetro livre e passa a ser derivado de uma conta que
  está escrita. Quem for aumentar a frota precisa aumentar o orçamento de conexão primeiro, e
  isso é uma restrição desconfortável de propósito.
- Autoscaling por CPU, que é o default de qualquer plataforma, fica explicitamente descartado
  aqui, e o motivo é medido: a CPU sobra enquanto o pool satura, então essa métrica não vê o
  limite que importa. É contraintuitivo o bastante para precisar estar escrito.
- Enquanto não existir um pooler entre as tasks e o banco, disponibilidade e throughput
  competem: cada task a mais por redundância consome do mesmo orçamento. `docs/scale.md`
  registra o pooler como o passo que desfaz esse conflito, e ele é a próxima peça de
  infraestrutura a existir.
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
instância doente e reconciliar contagem desejada é o trabalho que se estaria
reimplementando, e a topologia perderia a substituição por task que o canário usa.

**Função serverless por requisição.** Recusado, e o motivo é o mesmo orçamento de conexão
acima: um modelo que cria uma execução por requisição multiplica conexões contra um banco
cujo `max_connections` é finito e compartilhado. Seria a pior escolha possível para esta
restrição específica, salvo introduzindo um pooler como pré-requisito obrigatório em vez de
próximo passo.

**Dimensionar por CPU, como default.** Recusado pela medição: a fila está no pool e o host
mantém metade da CPU ociosa enquanto isso acontece, então a métrica que a plataforma usa por
padrão é cega para o recurso que de fato acaba.
