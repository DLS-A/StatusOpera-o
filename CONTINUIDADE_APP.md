# Continuidade do aplicativo ROTA GRM

Este documento registra o estado conhecido do projeto e o roteiro para publicar o backend, habilitar login Supabase, gerar o APK configurado e preparar a distribuição. Siga as etapas na ordem. Não coloque tokens, senhas ou chaves privadas no GitHub, no código Android ou em mensagens.

## 1. Estado atual

### Já existe no projeto

- Aplicativo Android Kotlin/Jetpack Compose em `android/`, com login OIDC por Authorization Code + PKCE, chamadas autenticadas ao backend, telas operacionais e alertas locais em primeiro plano.
- Backend FastAPI em `backend/`, que valida JWTs OIDC, consulta a API GRM e persiste snapshots no Postgres Supabase.
- Tela web de consentimento em `backend/app/oauth_consent.html` e `backend/app/oauth_consent.js`.
- Definição Render Blueprint em `render.yaml` e imagem Docker em `backend/Dockerfile`.
- Migrações SQL em `supabase/migrations/` para o schema privado `operational`, tabelas e índices.
- Um APK de debug antigo em `android/app/build/outputs/apk/debug/app-debug.apk`. Ele não deve ser tratado como o APK final, pois foi gerado antes de existirem o endereço real do serviço e o client ID final.
- O `.env` local está no `.gitignore`. Mantenha-o assim.

### Ainda não concluído (última conferência local)

- O repositório local está na branch `main` e contém o código e a documentação em commits locais. O remoto `origin` aponta para `https://github.com/DLS-A/StatusOpera-o.git`. O Git local recebeu HTTP 403 na tentativa de push; o conector GitHub conseguiu inicializar o remoto apenas com o `README.md`. Esse commit remoto já foi buscado e mesclado localmente, então o próximo push é fast-forward assim que o Git local usar uma conta com permissão de escrita.
- Não há confirmação de que o serviço Render foi criado ou implantado.
- O OAuth Server Supabase, o client público e os usuários autorizados ainda precisam ser configurados.
- O banco Supabase precisa receber as migrações do projeto.
- Os segredos de produção, o client ID e a lista de e-mails autorizados precisam ser configurados nos ambientes corretos.
- As propriedades locais de build Android ainda não contêm a URL real do backend nem o client ID final.
- Falta testar o ciclo real completo: login, autorização, leitura GRM e persistência.
- O APK atual é de debug; uma publicação ampla requer assinatura de release e distribuição escolhida.

> Estado local revisado em 26/10/2026. O status de serviços externos deve ser confirmado nos respectivos painéis antes de cada etapa.

Para instruções específicas de distribuição interna depois de preparar backend e login, consulte também [LANCAMENTO_MANUAL.md](LANCAMENTO_MANUAL.md).

## 2. Valores públicos e segredos

O projeto usa este issuer OIDC Supabase:

```text
https://byjmnvnojwssjswyeybj.supabase.co/auth/v1
```

O audience esperado é `authenticated`; o redirect Android exato é `com.grm.rotagrm:/oauth2redirect`.

Os valores abaixo são segredos e só devem ser inseridos nos campos protegidos do Render (ou em `.env` local para desenvolvimento):

- `URL_API_GRM`
- `TOKEN_API_APONTAMENTOS_GRM_PUSH`
- `SUPABASE_DB_URL` (inclui senha de banco)
- `OIDC_CLIENT_ID` é um identificador público do cliente, mas deve ser consistente com o cliente registrado e protegido contra alterações acidentais.
- `OIDC_ALLOWED_EMAILS` é uma lista de autorização; não é uma senha, mas deve ser mantida sob controle.

Não use `service_role`, secret key ou senha de banco em Kotlin, JavaScript do navegador, APK, `.env.example` ou commits. A publishable key do Supabase pode ser pública; a do projeto está declarada em `render.yaml`. Ela não substitui as regras de autorização do backend.

## 3. Publicar o código no GitHub

O Render precisa acessar um repositório com os arquivos e o `render.yaml` na raiz.

1. Abra o terminal na pasta raiz do projeto.
2. Confira o que será publicado:

   ```powershell
   git status --short --branch
   git check-ignore .env alertas_cache.json bot_startup.log log.txt
   ```

   `.env`, caches operacionais e logs devem aparecer como ignorados. Nunca force a inclusão desses arquivos. Confirme visualmente que nenhum arquivo com senha, token, banco de dados ou dado operacional aparece entre os arquivos a adicionar.

3. Adicione os arquivos de código, documentação, migrations e configuração necessários. Uma opção é:

   ```powershell
   git add .
   git status --short
   ```

   Antes do commit, revise a lista completa. Se `.env`, `alertas_cache.json`, logs ou outro arquivo privado aparecer, remova-o do stage com `git restore --staged <arquivo>` e corrija o `.gitignore`.

4. Crie o commit e publique em `main`:

   ```powershell
   git commit -m "Prepare authenticated Android app backend"
   git branch -M main
   git push -u origin main
   ```

5. No GitHub, confirme que `main` contém o código e `render.yaml`, e que não contém arquivos secretos.

#### Se o `git push` retornar HTTP 403

Na última tentativa, o GitHub recusou a publicação porque a conta autenticada no Git local não tinha permissão de escrita no repositório. Confira `git remote -v` e `gh auth status` (se GitHub CLI estiver instalado). Entre no Git com uma conta que tenha permissão **Write** ou superior para `DLS-A/StatusOpera-o`, ou peça ao administrador do repositório para concedê-la. Depois tente novamente:

```powershell
git push -u origin main
```

Não inclua token de acesso em URL, comando salvo, arquivo do projeto ou mensagem. O commit local permanece pronto na branch `main` até a autenticação correta ser configurada.

Se o Git solicitar identidade, configure `user.name` e `user.email` localmente antes do commit. Se solicitar autenticação, use o fluxo GitHub/credential manager; não cole token em arquivo versionado.

## 4. Preparar o banco Supabase

O backend grava cada consulta autenticada no schema privado `operational`. Sem as tabelas, `/v1/snapshot` responderá erro de serviço.

1. Entre no projeto Supabase `Status Operacional` (ref `byjmnvnojwssjswyeybj`).
2. Abra o **SQL Editor**.
3. Abra e execute, nesta ordem, os arquivos:
   - `supabase/migrations/20261023162717_create_operational_history.sql`
   - `supabase/migrations/20261023162855_index_snapshot_record_references.sql`
4. Confirme que existem `operational.snapshot_runs` e `operational.records`, que o índice `records_last_snapshot_id_idx` foi criado e que RLS está habilitado nas duas tabelas.
5. Em **Project Settings → Database → Connect**, copie a URI de conexão Postgres apropriada para conexão externa. Substitua a senha localmente, se necessário, e confirme que o endereço usa SSL. Não compartilhe a URI com senha.

As tabelas ficam num schema privado, sem acesso concedido aos papéis públicos `anon` e `authenticated`. O backend acessa o banco por conexão Postgres própria. Não exponha esse schema na Data API para resolver problemas de conexão.

## 5. Criar o backend no Render

1. Entre em Render e conecte a conta GitHub que tem acesso ao repositório.
2. Use **New → Blueprint** (ou a opção equivalente de criar Blueprint) e selecione `DLS-A/StatusOpera-o`, branch `main`.
3. Confirme que o Render detectou `render.yaml` e apresenta `rota-grm-backend`, runtime Docker, Dockerfile `backend/Dockerfile` e health check `/healthz`.
4. Revise o plano e a região antes de criar. O Blueprint está configurado com `0.5c-512mb`; o valor cobrado depende do preço vigente no painel Render. Confira a cotação atual antes de confirmar a criação.
5. Crie o serviço. Aguarde o build e o deploy inicial. Guarde a URL HTTPS fornecida pelo Render, por exemplo `https://rota-grm-backend-....onrender.com`.

O primeiro deploy pode iniciar sem todas as variáveis de autenticação; o health check `/healthz` não depende delas. O endpoint OAuth e as consultas só funcionarão depois que todas as configurações abaixo forem fornecidas.

### Variáveis de ambiente do Render

No serviço, abra **Environment**. Preencha os campos marcados como `sync: false` no Blueprint:

| Variável | Valor de onde vem | Observação |
|---|---|---|
| `URL_API_GRM` | `.env` local / operador da API GRM | URL da API autorizada; deve ser HTTPS. |
| `TOKEN_API_APONTAMENTOS_GRM_PUSH` | `.env` local / operador da API GRM | Segredo; não imprimir em logs. |
| `SUPABASE_DB_URL` | Supabase, **Database → Connect** | URI Postgres com senha e SSL; apenas no servidor. |
| `OIDC_CLIENT_ID` | Cliente OAuth público criado no passo 6 | Usar o mesmo valor no Android. |
| `OIDC_ALLOWED_EMAILS` | Operador do app | E-mails verificados autorizados, separados por vírgula. |

O Blueprint já define `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `OIDC_ISSUER` e `OIDC_AUDIENCE`. Não duplique esses valores sem necessidade. Use **Save, rebuild/deploy** depois de alterar variáveis. Nunca coloque os valores secretos no `render.yaml`.

## 6. Ativar Supabase Auth como provedor OIDC

O OAuth Server do Supabase é a função que fornece identidade ao aplicativo. Ela é diferente de usar Supabase como banco de dados. Consulte a documentação atual do Supabase, pois o OAuth Server é beta e a interface pode mudar: [Getting Started com OAuth Server](https://supabase.com/docs/guides/auth/oauth-server/getting-started) e [OAuth flows](https://supabase.com/docs/guides/auth/oauth-server/oauth-flows).

### 6.1 Habilitar e ajustar o servidor

1. No painel do projeto, abra **Authentication → OAuth Server** e habilite o recurso.
2. Configure o **Site URL** com a URL HTTPS real do serviço Render.
3. Defina o caminho de autorização como `/oauth/consent`. O backend serve a tela em `https://<servico-render>/oauth/consent`.
4. Em **Authentication → JWT Settings / Signing Keys** (nome pode variar), confirme assinatura assimétrica JWT com `RS256` ou `ES256`. O backend aceita esses algoritmos e consulta as chaves públicas JWKS do issuer; tokens HS256 serão recusados.
5. Confirme que o issuer permanece `https://byjmnvnojwssjswyeybj.supabase.co/auth/v1` e o audience emitido para o access token é `authenticated`.

### 6.2 Criar cliente OAuth Android

1. Registre o aplicativo como cliente OAuth **public** (sem client secret).
2. Configure Authorization Code + PKCE e método de autenticação do token endpoint `none`, conforme as opções disponíveis para cliente público.
3. Cadastre exatamente este redirect URI:

   ```text
   com.grm.rotagrm:/oauth2redirect
   ```

4. Solicite os escopos suportados que o app usa: `openid email profile`.
5. Copie o `client_id` público emitido. Configure-o como `OIDC_CLIENT_ID` no Render e como `rotaOidcClientId` no Gradle. Não crie nem use client secret no APK.

### 6.3 Usuários permitidos

1. Crie ou convide contas pelo fluxo administrativo adotado pela equipe. A tela do app permite login de contas existentes; não abre cadastro público.
2. Confirme cada endereço de e-mail no Supabase.
3. Configure `OIDC_ALLOWED_EMAILS` no Render com a lista de e-mails exatamente autorizados, separados por vírgula.
4. O backend exige `email_verified=true`, compara e-mail sem diferenciar maiúsculas/minúsculas e nega acesso se o usuário não estiver nessa lista.

Depois de alterar `OIDC_CLIENT_ID` ou allowlist no Render, faça novo deploy/restart. A configuração OIDC é carregada e armazenada em cache pelo processo.

## 7. Configurar o Android para o ambiente publicado

As propriedades Gradle são lidas no build e incorporadas ao APK como configuração pública. Não coloque segredos nelas. No Windows, crie/edite `%USERPROFILE%\.gradle\gradle.properties` (por exemplo, `C:\Users\<usuario>\.gradle\gradle.properties`) com:

```properties
rotaApiBaseUrl=https://<URL_REAL_DO_RENDER>
rotaOidcIssuer=https://byjmnvnojwssjswyeybj.supabase.co/auth/v1
rotaOidcClientId=<CLIENT_ID_PUBLICO_DO_SUPABASE>
rotaOidcAudience=authenticated
```

Use a URL base sem caminho `/v1`; o app forma a rota de snapshot a partir dela. Evite aspas e espaços ao redor dos valores. Não compartilhe esse arquivo se incluir outros dados locais.

### Gerar APK de debug

No PowerShell, a partir do diretório `android`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

O APK fica em:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

### Instalar e verificar no Android

Com Android SDK Platform Tools instalado e depuração USB autorizada:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Também é possível transferir o APK ao aparelho e abrir o arquivo, permitindo instalação de fonte conhecida conforme as configurações Android.

## 8. Roteiro de validação de ponta a ponta

Faça as verificações abaixo em ordem e registre o resultado/horário, sem registrar tokens:

1. Abra `https://<servico-render>/healthz`. Esperado: HTTP 200 e `{"status":"ok"}`.
2. Abra `https://<servico-render>/oauth/consent`. Esperado: página de login carregada por HTTPS. Se responder 503, confira `SUPABASE_URL` e `SUPABASE_PUBLISHABLE_KEY` no Render.
3. Faça login com um usuário Supabase permitido e verificado. Complete a autorização. Esperado: retorno ao app pelo URI `com.grm.rotagrm:/oauth2redirect`.
4. No app, carregue o snapshot. Esperado: dados de equipamentos, pausas e manutenções. A primeira chamada também grava dados no banco.
5. Para falha `401`, confira issuer, audience, validade do token, assinatura e relógio do dispositivo/servidor.
6. Para `403`, confirme `client_id`, `email_verified` e presença do e-mail em `OIDC_ALLOWED_EMAILS`.
7. Para `503` em `/v1/snapshot`, veja os logs de deploy e confira as variáveis GRM, `SUPABASE_DB_URL` e aplicação das migrations.
8. No Supabase SQL Editor, confira se apareceu uma linha recente em `operational.snapshot_runs` e se `operational.records` foi atualizado. Não abra nem exporte dados operacionais sem autorização.
9. Teste busca, filtros, detalhes, timers, permissões de notificação, som/silêncio e comportamento ao alternar entre telas em um aparelho real.

`/healthz` é apenas liveness: uma resposta OK não confirma que OIDC, GRM ou banco estejam funcionais. A validação real é o fluxo autenticado acima.

## 9. Distribuição e itens posteriores

O roteiro completo de distribuição interna manual está em [LANCAMENTO_MANUAL.md](LANCAMENTO_MANUAL.md). O APK de debug é adequado para validação controlada, não para distribuição formal. Para publicar:

1. Defina o canal de distribuição (instalação interna, Play Console ou outro aprovado).
2. Gere uma chave de assinatura de release e armazene-a em local seguro, com cópia de recuperação. Não a envie ao GitHub ou ao chat.
3. Configure as credenciais de assinatura no ambiente privado de build, incremente `versionCode`/`versionName` em `android/app/build.gradle.kts` e gere o bundle/APK de release.
4. Teste a versão assinada em dispositivos alvo e mantenha backup da chave: atualizações precisam da mesma chave.

### Alertas enquanto o app está suspenso

O app atual faz polling e alertas locais enquanto está em primeiro plano. Alertas confiáveis com app suspenso/encerrado ainda exigem um componente de monitoramento no servidor e push (por exemplo FCM), credenciais de servidor protegidas, política de repetição/deduplicação e testes em aparelhos Android. Não anuncie essa capacidade até implementar e validar o fluxo.

## 10. Checklist final

- [ ] `main` publicado no GitHub com `render.yaml`, sem `.env`, logs, caches ou segredos.
- [ ] Migrations executadas na ordem e tabelas privadas verificadas no Supabase.
- [ ] Blueprint importado; serviço Render criado e deploy concluído.
- [ ] Variáveis GRM, Postgres, client ID e allowlist configuradas no Render.
- [ ] OAuth Server ativo; URL/site/caminho e assinatura JWT assimétrica confirmados.
- [ ] Cliente OAuth public cadastrado com PKCE e redirect URI Android exato.
- [ ] Conta Supabase verificada e autorizada incluída na allowlist.
- [ ] Propriedades Gradle locais apontam para o serviço e client ID corretos.
- [ ] APK reconstruído depois da configuração e instalado em dispositivo.
- [ ] Login, consulta GRM e persistência Supabase validados de ponta a ponta.
- [ ] Decisão sobre assinatura/distribuição de release registrada.
- [ ] Decisão sobre push em segundo plano registrada (ainda não implementado).

## Referências oficiais

- [Supabase OAuth Server: configuração inicial](https://supabase.com/docs/guides/auth/oauth-server/getting-started)
- [Supabase OAuth flows](https://supabase.com/docs/guides/auth/oauth-server/oauth-flows)
- [Supabase JWT signing keys](https://supabase.com/docs/guides/auth/signing-keys)
- [Render Blueprint YAML](https://render.com/docs/blueprint-spec)
- [Render compute plans](https://render.com/docs/compute-plans)
