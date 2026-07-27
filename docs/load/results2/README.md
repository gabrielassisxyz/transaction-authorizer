# Dados brutos da varredura refeita

A primeira varredura de pool desta campanha manteve a carga oferecida fixa em 50 VUs
enquanto variava o tamanho do pool, então nos pontos maiores o pool nunca chegou a ser o
limitante e a curva não media o que a leitura dela supunha. Estes são os dados da refação,
com carga oferecida constante em 160 VUs, ordem não monotônica e um ponto de controle em
pool 20 repetido seis vezes.

| Arquivo | O que é |
|---|---|
| `k6/*.json` | Sumário do k6 de cada corrida. Prefixo `c` é controle em pool 20; `p` é o ponto sob teste |
| `pool.csv` | Gauges do HikariCP por segundo, com epoch: `active`, `idle`, `pending`, `max` |
| `cpu.csv` | CPU por container e ociosidade do host, com epoch |
| `manifest.csv` | Rótulo, VUs, início e fim de cada corrida, que é o que permite recortar as séries por janela |

A leitura está em [`../results.md`](../results.md), seção da varredura refeita.

Duas ressalvas sobre o que estes arquivos cobrem. A telemetria de pool só cobre as duas
últimas corridas: nas anteriores o coletor morria quando o container da aplicação era
recriado entre pontos, e a versão que sobreviveu foi escrita depois. E o SUT roda aplicação
e Postgres no mesmo host, então `cpu.csv` mostra os dois competindo pelos mesmos oito
núcleos, que é a razão de a ociosidade do host ser a coluna que importa ali.
