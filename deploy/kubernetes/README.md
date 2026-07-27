# Manifesto Kubernetes

O alvo de implantação deste serviço é ECS Fargate, e o [ADR-009](../../docs/adr/009-orquestrador-e-dimensionamento-da-frota.md)
argumenta a escolha. Este manifesto existe para tornar verificável a afirmação central
daquele ADR: a de que a escolha é reversível, porque nada no código conhece o orquestrador.

Cada peça aqui é a tradução de algo que já existe. Os dois probes são os grupos de health de
`application.yaml`, a espera de desligamento é a mesma que o consumer SQS precisa, e a
contagem de réplicas sai do orçamento de conexão em [`docs/scale.md`](../../docs/scale.md),
não do throughput.

A ausência mais importante é deliberada e está comentada no final do arquivo: não há
HorizontalPodAutoscaler, porque escalar por CPU aqui escala para dentro do problema.

## Verificar

```bash
kind create cluster --name authorizer-verify
docker build -t transaction-authorizer:scale-verify .
kind load docker-image transaction-authorizer:scale-verify --name authorizer-verify
# um Postgres e um Secret de teste, fora deste diretório porque não são parte da entrega
kubectl apply -f <postgres-e-secret-de-teste>.yaml
kubectl apply -f deploy/kubernetes/authorizer.yaml
kubectl rollout status deployment/authorizer
```

## O que a verificação provou

Rodado num cluster `kind` com um Postgres descartável e sem fila: o localstack não sobe
aqui, então o SQS fica inalcançável de propósito.

```
NAME                          READY   STATUS    RESTARTS   AGE
authorizer-67cf7c4dd9-bdgb5   1/1     Running   0          12s
authorizer-67cf7c4dd9-prhl8   1/1     Running   0          12s
```

Com a fila fora do ar, os três endpoints respondem assim:

```
/actuator/health/readiness  -> {"components":{"db":{...,"status":"UP"},...},"status":"UP"}
/actuator/health/liveness   -> {"components":{"livenessState":{"status":"UP"}},"status":"UP"}
/actuator/health            -> ...,"sqs":{...,"error":"SdkClientException","status":"DOWN"},...
```

É a separação dos grupos de health funcionando dentro de um orquestrador real: o SQS está
DOWN e as réplicas continuam em rotação, porque a readiness segue o banco e a fila é um
componente à parte. Uma fila fora do ar precisa alertar sem tirar o autorizador do
balanceador, já que a via HTTP continua sã.

O `/actuator/info` responde a revisão que está atendendo, que é o que um rollout em degraus
precisa para atribuir um sinal ruim à versão certa:

```
{"build":{"artifact":"transaction-authorizer","version":"0.1.0","time":"..."}}
```

## O defeito que a aplicação revelou

A primeira versão deste manifesto não subia. `runAsNonRoot: true` combinado com o `USER app`
do Dockerfile falha com `container has runAsNonRoot and image has non-numeric user`: o
kubelet precisa decidir se o usuário é root antes de existir container onde resolver o
nome, então um nome não serve e o UID precisa ser numérico.

Fica registrado porque é o argumento para não versionar manifesto que ninguém aplicou. O
arquivo estaria plausível, revisável e quebrado.

## O que este manifesto não é

Não é infraestrutura como código de produção. Não há Ingress, autoscaling, NetworkPolicy,
gestão de segredo real nem overlay por ambiente, e o `Secret` referenciado é injetado de fora
porque credencial em produção vem da role da task, não de manifesto. Para valer como
implantação de verdade, faltaria a camada que o [`docs/deploy.md`](../../docs/deploy.md)
descreve, e ela é o alvo do ADR-009.
