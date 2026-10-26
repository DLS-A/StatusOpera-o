# Lançamento manual do ROTA GRM

Este roteiro é para distribuir o app diretamente a um grupo interno, instalando um APK assinado fora da Play Store. A publicação manual só deve começar depois que os bloqueios de backend e login forem resolvidos.

## Estado de prontidão

O código local está na branch `main`, com os commits preparados e o commit inicial do GitHub já mesclado. O remoto `https://github.com/DLS-A/StatusOpera-o.git` recebeu somente o README; o código completo ainda aguarda o push. O Git local retornou HTTP 403 porque a conta autenticada não tem permissão de escrita. Ainda não há URL do Render nem client ID final do Supabase neste projeto. O APK existente é antigo e de debug.

**Portanto, o APK existente não está pronto para entrega aos usuários.** Não gere nem distribua um APK release até completar as etapas marcadas como bloqueadoras abaixo.

## Fase 1 — Desbloquear publicação do código

1. Autentique o Git local com uma conta que tenha acesso de escrita ao repositório `DLS-A/StatusOpera-o`, ou solicite ao administrador do repositório a permissão necessária. O remoto já foi inicializado com o README e mesclado nesta branch local.
2. Na raiz do projeto, confirme o destino e a branch:

   ```powershell
   git remote -v
   git status --short --branch
   ```

3. Confirme que a branch é `main`, que o estado está limpo e que `.env`, `alertas_cache.json`, logs e outros dados operacionais não estão no stage.
4. Envie os commits locais:

   ```powershell
   git push -u origin main
   ```

5. No GitHub, confira os arquivos publicados e confirme que `render.yaml` está na raiz. O `.env` e dados privados não devem estar no repositório.

## Fase 2 — Preparar backend e banco

1. No Supabase, execute em ordem as duas migrations em `supabase/migrations/` pelo SQL Editor. Confirme as tabelas `operational.snapshot_runs` e `operational.records` e RLS habilitado.
2. No painel Supabase, habilite **Authentication → OAuth Server** e configure assinatura JWT assimétrica (`RS256` ou `ES256`).
3. No Render, crie um Blueprint ligado ao repositório `DLS-A/StatusOpera-o`, branch `main`, usando `render.yaml`. Revise plano/preço antes de criar o serviço.
4. No Render, preencha apenas no painel as variáveis `URL_API_GRM`, `TOKEN_API_APONTAMENTOS_GRM_PUSH` e `SUPABASE_DB_URL`. Nunca coloque esses segredos no GitHub ou no app.
5. Aguarde o primeiro deploy e copie o domínio HTTPS que o Render atribuiu. Confirme `https://<dominio>/healthz` retorna `{"status":"ok"}`.

## Fase 3 — Ativar login OIDC

1. No Supabase OAuth Server, configure o Site URL com o domínio HTTPS do Render e o Authorization Path como `/oauth/consent`.
2. Registre um cliente OAuth Android **public**, sem client secret, com Authorization Code + PKCE e redirect URI exata:

   ```text
   com.grm.rotagrm:/oauth2redirect
   ```

3. Use escopos `openid email profile` e copie o client ID público.
4. Crie ou convide os usuários no Supabase e confirme os e-mails.
5. No Render, preencha `OIDC_CLIENT_ID` e `OIDC_ALLOWED_EMAILS` (e-mails verificados autorizados, separados por vírgula) e faça deploy/restart.
6. Abra `https://<dominio>/oauth/consent` e confirme que a página carrega.

## Fase 4 — Configurar e validar o app

No arquivo `%USERPROFILE%\.gradle\gradle.properties`, configure os valores públicos do ambiente de produção:

```properties
rotaApiBaseUrl=https://<dominio-real-do-render>
rotaOidcIssuer=https://byjmnvnojwssjswyeybj.supabase.co/auth/v1
rotaOidcClientId=<client-id-publico>
rotaOidcAudience=authenticated
```

Antes de assinar o APK, instale uma build de validação num aparelho e confirme:

- login com usuário autorizado e retorno pelo redirect Android;
- carregamento de `/v1/snapshot` com dados GRM;
- persistência de snapshot no Supabase;
- busca, filtros, detalhes, timers e alertas enquanto o app está aberto;
- notificações e permissões nas versões Android usadas pela equipe;
- ausência de token ou credencial em logs e telas.

O endpoint `/healthz` sozinho não valida login, API GRM nem banco.

## Fase 5 — Assinar APK para distribuição interna

1. Escolha um responsável pela chave de assinatura. Gere uma keystore de release usando **Android Studio → Build → Generate Signed Bundle / APK → APK → Create new** (ou selecione a keystore existente da organização).
2. Guarde a keystore, alias, senhas e uma cópia de recuperação num cofre corporativo. Não envie para GitHub, chat ou e-mail sem proteção.
3. Mantenha a mesma keystore para todas as atualizações do app. Se ela for perdida, os usuários não poderão atualizar a instalação assinada com essa chave sem remover o app.
4. No Android Studio, selecione o módulo `app`, escolha **release**, informe a keystore e gere o APK assinado. Use as propriedades Gradle de produção definidas na fase anterior.
5. Atualize `versionCode` (sempre crescente) e `versionName` em `android/app/build.gradle.kts` antes de cada nova distribuição.
6. Instale o APK assinado em pelo menos um dispositivo de validação antes de enviá-lo ao grupo.
7. Registre o checksum do arquivo e a versão distribuída:

   ```powershell
   Get-FileHash .\app\release\app-release.apk -Algorithm SHA256
   ```

   O caminho real pode variar conforme a saída do Android Studio. Compartilhe o APK e o SHA-256 por um canal interno aprovado; não publique segredos junto do instalador.

## Fase 6 — Entrega aos usuários

1. Envie o APK assinado, a versão e o SHA-256 pelo canal interno autorizado.
2. Oriente o usuário a confirmar a origem do arquivo, permitir instalação dessa fonte quando o Android solicitar e abrir o APK.
3. Distribua primeiro a um grupo pequeno. Registre versão do Android, resultado do login e eventuais erros sem coletar tokens.
4. Após validação, libere ao grupo restante e mantenha uma cópia do APK, checksum, versão e data da distribuição.

## Fora do escopo do lançamento manual atual

- A build debug existente não é para distribuição formal.
- Push/monitoramento quando o app está suspenso ou encerrado ainda não está implementado. Os alertas atuais funcionam enquanto o app está em primeiro plano.
- Não existe ainda evidência de teste ponta a ponta em aparelho com backend e usuários reais.
- Play Store, assinatura administrada pelo Google e atualização automática não fazem parte deste roteiro.

## Checklist de liberação

- [ ] Push da branch `main` concluído sem arquivos privados.
- [ ] Migrations aplicadas e tabelas verificadas.
- [ ] Serviço HTTPS Render em produção e variáveis secretas configuradas.
- [ ] OAuth Server, client público e allowlist Supabase configurados.
- [ ] APK configurado com URL/issuer/client ID corretos.
- [ ] Fluxo real validado em dispositivo e snapshot gravado no banco.
- [ ] APK release assinado com keystore protegida.
- [ ] Versão, SHA-256 e canal interno registrados.
- [ ] Usuários informados sobre limitações dos alertas em segundo plano.
