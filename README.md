# ROTA GRM — fontes atualizadas

**Entrega de código, não de aplicativo instalado.** Trabalho executado em cópia separada do projeto recebido. Mantidos Kotlin/Jetpack Compose/Material 3, FastAPI, Supabase e Render; módulo canônico `android/`. O original não foi editado. Não há APK novo, deploy ou validação operacional real nesta entrega.

## Mudanças principais

- Consulta GRM GET centralizada por ciclo, com coordenação PostgreSQL entre instâncias; celulares apenas leem o snapshot compartilhado.
- Payload persistido, restauração após reinício e último snapshot válido marcado como desatualizado durante falhas.
- JWT assimétrico, cliente/audience/issuer, usuário confirmado e autorizado, sessão ativa e revogação local do servidor.
- Cache Android cifrado por usuário; isolamento entre sessões, cancelamento de consultas, logout e erros 401/403 tratados.
- Cinco abas e Material 3 preservados; banner de frescor e contagens operacionais apenas com categorias KPI confirmadas.
- FCM opcional, registro de dispositivo autorizado, fila privada e payload genérico; desabilitado por padrão.

## Antes de usar

1. **Revogar e substituir credenciais expostas** em `env`/logs do projeto original. Esses arquivos não foram incluídos. Exclusão do pacote não revoga tokens.
2. Seguir [instalação administrativa](docs/instalacao.md): Supabase, migrations, CA/TLS, Render/OAuth, Firebase opcional e assinatura externa.
3. Preencher os identificadores públicos de `android/rota-public.properties` uma vez por build. Não configurar a API por celular, nem colocar token GRM/senha do banco no APK.
4. Executar [validação operacional](docs/validacao.md). Login real, GRM/Supabase, FCM/aparelho e Android build/runtime continuam pendentes.

## Organização

| Diretório/arquivo | Função |
|---|---|
| `android/` | Aplicativo nativo, versão de fontes 0.2.0, sem binário compilado |
| `backend/app/` | API autenticada, snapshots compartilhados, sessões e FCM |
| `supabase/migrations/` | Três migrations ordenadas, tabelas operacionais privadas |
| `render.yaml`, `backend/Dockerfile` | Preparação de um serviço existente; não executar sem autorização administrativa |
| `.env.example` | Modelo sem segredos preenchidos |
| `test_*.py`, `backend/requirements-test.txt` | Testes locais isolados |
| `docs/` | Instalação, uso, validação, resultados e manifestos |
| `bot_start.py`, `monitor.py`, `functions.py` | Bot legado separado; não faz parte do loop de snapshots do app |

## API

- Público: `GET /healthz` (somente processo); páginas `/oauth/consent` e `/oauth/consent.js`.
- Bearer obrigatório: `GET /v1/snapshot`, `/v1/readiness`, `/v1/session`.
- Sessão/notificação: `POST /v1/session/logout`, `PUT`/`DELETE /v1/devices/{uuid}`.
- Nenhuma escrita operacional GRM. POST/PUT/PATCH/DELETE no snapshot retornam 405.
- `/v1/snapshot` não consulta GRM nem grava histórico; leitura autenticada continua verificando usuário e sessão. Sem snapshot válido: 503. Último válido disponível em falha: 200 com `stale=true`.
- `healthz=200` não comprova login, banco, GRM, atualização ou push. OpenAPI permanece desativado.

## Testes

Instalar dependências de teste em ambiente isolado e executar:

```sh
python -m pip install -r backend/requirements-test.txt
python -m unittest -v test_datetime_grm test_backend_snapshot test_security_services
```

A integração PostgreSQL requer opt-in explícito e banco **local descartável** `rota_grm_local_test`; não lê a URL Supabase:

```sh
RUN_LOCAL_POSTGRES=1 python -m unittest -v test_postgres_integration
```

Consulte [resultados completos](docs/test-results.txt) e [limites de evidência](docs/validacao.md). Parsing estático Kotlin não é compilação, teste JUnit executado nem validação visual. Os testes Android presentes devem ser executados fora deste sandbox.

## Limites importantes

O contrato GRM preserva a janela do início do dia anterior em São Paulo até agora UTC. Registros abertos anteriores à janela podem não ser retornados; não foi inventado filtro ou ampliada a janela. Notificações não substituem monitoramento de segurança: FCM, rede, permissões, bateria e servidor podem impedir entrega. Não há promessa de push após force-stop. Nenhum serviço pago foi contratado ou alterado.
