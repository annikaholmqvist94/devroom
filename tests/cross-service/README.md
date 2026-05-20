# Cross-service tests

Modulen innehåller tester som spänner *över* tjänstegränser. Service-lokala
integrationstester ligger kvar per modul (`services/*/src/test/java`) och täcker
varje tjänst end-to-end mot sina egna externa beroenden (Postgres, RabbitMQ,
gRPC, OIDC).

## Strategi: pragmatisk minimum

Plan 09 övervägde tre strategier för cross-service-tester:

| # | Strategi | Status | Motivering |
|---|---|---|---|
| 1 | In-process multi-context — starta `AuthServiceApplication` + `UserServiceApplication` i samma JVM mot delade Testcontainers | Skjuts | `application.yml`-kollision på classpath + auto-config-blödning mellan Boot-applikationer i samma JVM gör testen fragil att underhålla |
| 2 | Docker Compose-baserad e2e via `org.testcontainers.containers.ComposeContainer` | Skjuts till plan 10 | Kräver Dockerfiles per tjänst, vilket byggs i plan 10. `docker-compose.yml` har idag bara infra (Postgres × 3 + RabbitMQ), inte service-containrar |
| 3 | Kontrakt-test mellan tjänsterna in-process (utan att boota Spring) | **Implementerad** | Lånar in service-klasser i test-scope, anropar dem direkt med mockade I/O-deps, verifierar JSON-schemat över wiren |

Strategi 3 vald eftersom:
- Service-lokala tester (17 st över 5 moduler) täcker redan varje tjänsts egen kontrakt mot sina externa beroenden.
- Det som *inte* fångas av service-lokala tester är schemat *mellan* tjänsterna — t.ex. att Auths `user.registered`-payload kan parsas av Users consumer.
- Strategi 3 fångar precis det gapet, körs på under en sekund, och kräver ingen Docker.

## Befintligt cross-service-test

### `UserRegisteredEventContractTest`

Verifierar kontraktet på `user.registered`-eventet mellan Auth Service (producent)
och User Service (konsument):

1. Instansierar `SignupService` (Auth) med mockade JPA-repositories.
2. Anropar `signup("test@example.com", "pw")` och fångar `OutboxEvent.payload()`.
3. Instansierar `UserRegisteredConsumer` (User) med en mockad `UserRegisteredHandler`.
4. Kör payload-JSONet genom `consumer.onMessage(json)`.
5. Asserterar att handlern fick rätt `userId / email / teamId`.

Om någon byter t.ex. `user_id` → `userId` i Auths payload eller User-Service consumer
fail:ar denna test omedelbart — vilket service-lokala tester inte fångar.

## Vad som *inte* finns här

- Multi-Spring-context e2e-tester (skjutna; se plan 10).
- Compose-baserade tester (skjutna till plan 10 när Dockerfiles finns).
- Manuell smoke-test av frontend mot full stacken (separat aktivitet).

## Köra testerna

```bash
mvn -pl tests/cross-service -am -B verify
```
