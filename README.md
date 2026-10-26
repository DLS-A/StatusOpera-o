# Bot de Status Operacional GRM

## Continuidade do app Android

O estado, as pendências e o passo a passo detalhado de publicação do backend, configuração Supabase/OIDC, build e validação estão em [CONTINUIDADE_APP.md](CONTINUIDADE_APP.md).
Para a distribuição interna manual do APK assinado, siga também [LANCAMENTO_MANUAL.md](LANCAMENTO_MANUAL.md).

O bot Python legado foi preservado. O app Android em `android/` consulta dados pelo servico autenticado em `backend/`. As credenciais da API GRM ficam no ambiente do servidor; o backend falha fechado se o provedor OIDC nao estiver configurado ou disponivel.

## Seguranca e configuracao Python

1. Mantenha valores privados no `.env` local ou nas variaveis do servidor. Nunca os copie para `keys.py`, Kotlin, logs ou APK.
2. Use `.env.example` somente como modelo.
3. A API GRM deve usar HTTPS. A configuracao rejeita URLs sem HTTPS.
4. Para iniciar o bot legado, configure `TOKEN_BOT_GRM` e `CHAT_IDS_ALERTA`.

O `start_bot.bat` usa a pasta do proprio script. Falhas DNS do Telegram sao tratadas com repeticao progressiva e logs sem o texto bruto das excecoes HTTP.

## Backend

Instale `backend/requirements.txt` no ambiente virtual e configure no servidor:

- `URL_API_GRM` e `TOKEN_API_APONTAMENTOS_GRM_PUSH`;
- `SUPABASE_DB_URL` com a URI Postgres do projeto Supabase (somente no servidor; SSL e exigido);
- `OIDC_ISSUER`, `OIDC_AUDIENCE`, `OIDC_CLIENT_ID` e `OIDC_ALLOWED_EMAILS` para Supabase Auth OAuth Server.

O backend pode ser publicado no Render pelo `render.yaml`. Configure as chaves na secao Environment do servico. O OAuth Server do Supabase deve usar assinatura JWT assimetrica, client mobile publico com redirect `com.grm.rotagrm:/oauth2redirect`, site URL apontando para o backend Render e caminho `/oauth/consent`. A tela de consentimento usa somente contas Supabase existentes; o backend restringe o acesso aos emails verificados configurados em `OIDC_ALLOWED_EMAILS`. O cliente Supabase do navegador usa apenas a publishable key, que e publica; nunca use uma secret/service role key no app ou na tela.

Execute localmente:

```powershell
python -m pip install -r backend/requirements.txt
python -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000
```

`GET /healthz` verifica somente o processo. `GET /v1/snapshot` exige token Bearer com assinatura assimetrica OIDC valida, issuer e audience esperados, client ID do app e email verificado presente no allowlist. OpenAPI fica desativado.

Cada consulta autenticada grava um resumo em `operational.snapshot_runs` e atualiza os apontamentos vistos em `operational.records`, preservando quando cada registro apareceu pela primeira vez. As tabelas ficam no schema privado `operational`, com RLS ligado e sem acesso para os papeis da API publica. Configure a URI no ambiente do backend; nao use a senha do banco no Android. Se o servidor nao alcancar o banco, `/v1/snapshot` retorna 503 em vez de confirmar uma consulta sem persistencia. A migration aplicada esta em `supabase/migrations/`.

Para deploy, construa `docker build -f backend/Dockerfile -t rota-grm-backend .`, injete segredos em runtime e mantenha o servico em rede privada atras de proxy TLS.

## Android e estado atual

- O build debug foi concluido. APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- O app implementa login OIDC com PKCE, sessao cifrada com AES-GCM/Android Keystore, consulta autenticada HTTPS, busca/detalhes/timers de equipamento, telas de pausas/manutencoes e configuracoes DataStore com alertas locais por limite.
- Para conectar, configure as propriedades Gradle documentadas em `android/README.md`, cadastre o cliente Android publico no provedor OIDC e publique o backend.
- O polling e alertas locais ocorrem em primeiro plano. Alertas com o app suspenso requerem monitoramento no servidor e push, ainda pendentes.
- A regra distinta do bot para novas manutencoes do dia foi conectada ao filtro existente no backend e deduplicada por ID no app. Push com o app suspenso e assinatura de producao ainda requerem configuracao e validacao.

Confirme timestamps e unidade com dados reais do GRM antes do uso operacional. Os dados e logs antigos foram preservados; revise-os conforme a politica da operacao. Novas consultas nao exportam automaticamente o conteudo operacional para arquivo JSON.

## Validacao e proximas dependencias

- Validacao executada: 6 testes unitarios Android e 12 testes Python (8 do bot e 4 do backend), sem falhas; `assembleDebug` passou.
- Nao havia celular ou emulador conectado para validar a interface, permissao de notificacao e som no Android real.
- Login e snapshot com dados reais ainda exigem backend publicado e propriedades OIDC configuradas. Nao envie tokens GRM ou chaves privadas pelo chat.
- Push com o app suspenso exige um projeto Firebase/FCM e credenciais de servidor configuradas fora do APK. O APK atual so consulta em primeiro plano.
- A distribuicao de producao exige uma chave de assinatura fornecida e guardada pelo operador; o APK existente e de depuracao.
# StatusOpera-o

