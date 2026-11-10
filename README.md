# Status Operacional — ativação por telefone

**Backend publicado e dados reais validados.** Mantidos Kotlin/Jetpack Compose/Material 3, FastAPI, Supabase e Render; módulo canônico `android/`. Original e GitHub main preservados. Nesta versão, um código compartilhado ativa cada telefone uma única vez, sem e-mail/login. Consulte [ativação e implantação atual](docs/ativacao.md), que prevalece sobre os documentos históricos de OAuth.

## Mudanças principais

- Consulta GRM GET centralizada por ciclo, com coordenação PostgreSQL entre instâncias; celulares apenas leem o snapshot compartilhado.
- Payload persistido, restauração após reinício e último snapshot válido marcado como desatualizado durante falhas.
- Credencial opaca individual por telefone, hash no banco, revogação individual e limitação persistente das tentativas de ativação.
- Cache Android cifrado por usuário; isolamento entre sessões, cancelamento de consultas, logout e erros 401/403 tratados.
- Cinco abas e Material 3 preservados; banner de frescor e contagens operacionais apenas com categorias KPI confirmadas.
- FCM opcional, registro de dispositivo autorizado, fila privada e payload genérico; desabilitado por padrão.

## Antes de usar

1. **Revogar e substituir credenciais expostas** em `env`/logs do projeto original. Esses arquivos não foram incluídos. Exclusão do pacote não revoga tokens.
2. Seguir [documentação atual](docs/ativacao.md): ativação, Supabase privado, TLS e Render gratuito. As instruções OAuth antigas não se aplicam a esta implantação.
3. A URL HTTPS real já está em `android/rota-public.properties`. Nunca incluir código compartilhado, token GRM ou senha do banco no APK.
4. Validar instalação/interface no telefone. O backend foi testado remotamente; isso não comprova o runtime Android no aparelho.

## Organização

| Diretório/arquivo | Função |
|---|---|
| `android/` | Aplicativo nativo 0.4.0-test, build externo GitHub Actions |
| `backend/app/` | API autenticada, snapshots compartilhados, sessões e FCM |
| `supabase/migrations/` | Quatro migrations ordenadas, tabelas operacionais privadas |
| `render.yaml`, `backend/Dockerfile` | Serviço Docker no Render gratuito, implantação autorizada |
| `.env.example` | Modelo sem segredos preenchidos |
| `test_*.py`, `backend/requirements-test.txt` | Testes locais isolados |
| `docs/` | Instalação, uso, validação, resultados e manifestos |
| `bot_start.py`, `monitor.py`, `functions.py` | Bot legado separado; não faz parte do loop de snapshots do app |

## API

- Público: `GET /healthz` (somente processo); `POST /v1/activation` (código obrigatório, tentativas limitadas).
- Bearer obrigatório: `GET /v1/snapshot`, `/v1/readiness`, `/v1/session`.
- Desativação: `POST /v1/session/logout`. Rotas FCM recusam credenciais de ativação; push desabilitado nesta implantação. Páginas OAuth retornam 404.
- Nenhuma escrita operacional GRM. POST/PUT/PATCH/DELETE no snapshot retornam 405.
- `/v1/snapshot` não consulta GRM nem grava histórico; cada leitura verifica a credencial individual no banco. Sem snapshot válido: 503. Último válido disponível em falha: 200 com `stale=true`.
- `healthz=200` não comprova login, banco, GRM, atualização ou push. OpenAPI permanece desativado.

## Testes

Instalar dependências de teste em ambiente isolado e executar:

```sh
python -m pip install -r backend/requirements-test.txt
python -m unittest -v test_datetime_grm test_backend_snapshot test_security_services test_activation
```

A integração PostgreSQL requer opt-in explícito e banco **local descartável** `rota_grm_local_test`; não lê a URL Supabase:

```sh
RUN_LOCAL_POSTGRES=1 python -m unittest -v test_postgres_integration
```

Consulte [resultados completos](docs/test-results.txt) e [limites de evidência](docs/validacao.md). Parsing estático Kotlin não é compilação, teste JUnit executado nem validação visual. Os testes Android presentes devem ser executados fora deste sandbox.

## Limites importantes

O contrato GRM preserva a janela do início do dia anterior em São Paulo até agora UTC. Registros abertos anteriores à janela podem não ser retornados; não foi inventado filtro ou ampliada a janela. Notificações não substituem monitoramento de segurança: FCM, rede, permissões, bateria e servidor podem impedir entrega. Não há promessa de push após force-stop. Nenhum serviço pago foi contratado ou alterado.
