# Provisionamento do par de carga

Duas máquinas isoladas, uma gera carga e a outra é o SUT, para que a medição valha. Rodar
gerador e servidor no mesmo host mede loopback: o gerador rouba CPU do servidor e o tráfego
nunca cruza uma rede. Este runbook sobe as duas, dimensiona o SUT para que a saturação seja
alcançável e derruba tudo ao final.

Provisione quando a fase de resiliência começa, não quando a campanha de carga começa. O
tempo de espera das máquinas é a armadilha clássica: descobrir na véspera da entrega que os
números de loopback não valem e correr atrás de uma segunda máquina.

## As duas máquinas

| Papel | Instala | Grupo de segurança |
|---|---|---|
| Gerador | binário k6 | saída para a porta HTTP do SUT; SSH de entrada |
| SUT | Docker e a stack do compose | entrada na porta HTTP a partir do gerador; SSH |

Mesma zona de disponibilidade, para que a latência de rede seja a de um salto real e não a
de uma travessia entre regiões. Os grupos de segurança abrem só o par mais SSH: nada de
expor a porta HTTP do SUT à internet.

O SUT é dimensionado modestamente de propósito. O objetivo não é o número mais alto
possível, é alcançar a saturação e observar o comportamento no limite; uma máquina grande
demais só empurra o teto para longe sem revelar nada. Registre o tipo de instância de cada
máquina na tabela de resultados antes da primeira corrida.

## Semear o SUT

O caminho medido é só HTTP, mas ele precisa de contas reais para acertar. A semente vem da
própria stack do compose:

1. No SUT, suba a stack com o app:

   ```sh
   APP_BIND=0.0.0.0 docker compose --profile app up -d --build
   ```

   O app fica atrás do profile `app`, então sem ele a stack sobe Postgres, localstack e o
   gerador mas nenhum consumer, e a semente ficaria parada na fila. Com o profile, sobe
   também o autorizador conteinerizado, que é o próprio SUT da campanha.

   `APP_BIND` é o que torna a campanha possível. O compose publica a porta em `127.0.0.1`
   por padrão, para que uma máquina de desenvolvimento não coloque o autorizador na rede
   local; com a porta em loopback, o gerador recebe conexão recusada mesmo com o grupo de
   segurança correto, e o sintoma é indistinguível de app fora do ar. No SUT quem restringe
   o alcance é o grupo de segurança, que abre a porta só para o gerador.

   Os valores de tuning viajam pelo mesmo caminho: `DB_POOL_SIZE` e `SQS_POLLERS` são
   repassados ao container e assumem os defaults da aplicação quando não declarados, então
   uma varredura de pool é `DB_POOL_SIZE=40 docker compose --profile app up -d`, sem editar
   arquivo nenhum entre corridas.
2. Espere `message-generator exited with code 0` e o consumer do app drenar a fila. As
   contas passam a existir no Postgres do SUT.
3. Durante as corridas o localstack fica ocioso: o tráfego medido não toca a fila. Isso
   está declarado nos caveats do [README](README.md).

Depois da drenagem, extraia a amostra de ids que o k6 vai acertar:

```sh
cd docs/load/scripts
./extract-accounts.sh
```

## Checklist de teardown

Estas máquinas já foram esquecidas ligadas uma vez; disciplina de custo. Ao fim da campanha:

- [ ] Números e gráficos copiados do SUT para [results.md](results.md) e o diretório de
      resultados, commitados. Nada de valor mora só na máquina.
- [ ] Instância do gerador terminada.
- [ ] Instância do SUT terminada.
- [ ] Volumes e IPs elásticos associados liberados, para não deixar cobrança órfã.
- [ ] Grupos de segurança criados para a campanha removidos.
- [ ] Chave de acesso da CLI usada na campanha apagada. Ela não expira sozinha, então
      sobrevive à destruição das máquinas e continua valendo na conta inteira.

A campanha termina quando as duas máquinas não existem mais, não quando a última corrida
acaba.
