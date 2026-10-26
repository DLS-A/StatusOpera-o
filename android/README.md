# Aplicativo Android ROTA GRM

Para o roteiro atualizado de publicação, variáveis do Render, configuração Supabase/OIDC, validação e pendências de produção, consulte [CONTINUIDADE_APP.md](../CONTINUIDADE_APP.md). Esse documento é a referência principal para concluir o app.
Para assinar e distribuir o APK manualmente, siga [LANCAMENTO_MANUAL.md](../LANCAMENTO_MANUAL.md).

Este modulo permanece separado do bot Python. O app usa Kotlin, Jetpack Compose e Material 3. O login OIDC usa Authorization Code + PKCE pelo AppAuth; o estado OAuth persistido fica cifrado com AES-GCM e uma chave do Android Keystore. O cliente consulta apenas o backend autenticado em HTTPS. Nenhum token GRM ou segredo de servidor deve ser distribuido no APK.

## Versoes

- Android Gradle Plugin 9.2.0 e Gradle 9.4.1.
- Kotlin embutido no AGP 9.2; plugin Compose 2.2.10.
- Compose BOM 2026.09.00, DataStore 1.2.0 e Lifecycle 2.11.0.
- SDK compile 37, target 36 e Android minimo 8.0 (API 26).

## Propriedades locais

Configure estas propriedades no `~/.gradle/gradle.properties` do usuario. Sao enderecos e identificadores publicos, sem segredos:

```properties
rotaApiBaseUrl=https://URL_REAL_DO_SERVICO.onrender.com
rotaOidcIssuer=https://byjmnvnojwssjswyeybj.supabase.co/auth/v1
rotaOidcClientId=CLIENT_ID_PUBLICO_CRIADO_NO_SUPABASE
rotaOidcAudience=authenticated
```

O Supabase Auth OAuth Server usa Authorization Code + PKCE. Cadastre um cliente **Public** com redirect URI exata `com.grm.rotagrm:/oauth2redirect` e sem client secret. Ative o OAuth Server, configure o site URL para o domínio Render real e o caminho de autorização como `/oauth/consent`. A tela de consentimento está no backend; crie previamente no Supabase as contas que poderão acessar o app. Em `OIDC_ALLOWED_EMAILS`, liste somente emails verificados autorizados. Configure o Supabase para assinar JWTs com chave assimétrica (RS256 ou ES256); o backend valida o issuer, `aud=authenticated`, o client ID e o allowlist de emails. O OAuth Server do Supabase não aceita o escopo personalizado `rota.grm.read`.

O Render publica o backend por HTTPS. O serviço está definido em `render.yaml`, mas precisa ser criado no painel Render depois que este repositório tiver o código enviado ao GitHub. O plano configurado é `0.5c-512mb` (US$ 7/mês na tabela consultada em 23/10/2026); nenhum serviço foi criado nem cobrado. Preencha no Render `URL_API_GRM`, `TOKEN_API_APONTAMENTOS_GRM_PUSH`, `SUPABASE_DB_URL`, `SUPABASE_PUBLISHABLE_KEY`, `OIDC_CLIENT_ID` e `OIDC_ALLOWED_EMAILS` usando os campos de segredo do painel. Não coloque esses valores no repositório.

## Arquitetura

- `auth`: OIDC, PKCE e armazenamento cifrado da sessao.
- `data`: cliente HTTP HTTPS com redirecionamentos desabilitados, limite de 10 MiB por resposta e validacao do formato do snapshot; respostas incompletas sao tratadas como falha para preservar o ultimo estado valido.
- `domain`: modelos operacionais e contrato do repositorio.
- `settings`: preferencias em DataStore para intervalo, limites por status, repeticao e som.
- UI Compose: inicio, equipamentos com pesquisa/filtros por status/detalhes, pausas, manutencoes e configuracoes. O estado da permissao de notificacoes e atualizado quando o app volta ao primeiro plano.
- Backend: valida identidade e consulta GRM; credenciais nunca vao para o APK.

O polling configuravel (60 segundos por padrao; 30 a 3600 segundos) ocorre somente enquanto a tela esta em primeiro plano. Timers usam o epoch de inicio retornado pelo backend e exibem a data explicitamente em UTC ate validar o fuso com a operacao. O limite padrao de pausa ergonomica e 15 minutos; os demais status nao recebem limite automaticamente. Alertas locais permitem reconhecer/silenciar e repetir (5 minutos por padrao), e usam o canal de notificacao do Android para som ou modo silencioso. Reconhecimento e horario da ultima notificacao ficam no DataStore local. O volume fica sob controle do Android.

Alertas locais nao continuam consultando quando a tela fica suspensa. O endpoint do backend inclui manutencoes elegiveis segundo a funcao original `obter_manutencoes_atuais` (iniciadas no dia de `America/Sao_Paulo`); o app as deduplica por ID entre consultas e nao repete o aviso. Para alertas com tela bloqueada/suspensao, implemente monitoramento no backend com push autenticado. Nao declarar suporte apos encerramento forcado sem implementar e validar esse componente.

## Verificacao

O build debug e os 6 testes unitarios de regras de alerta/deduplicacao foram executados com Gradle. Os testes Python do projeto tambem cobrem regras temporais, falha de consulta, endpoint snapshot e autenticacao fail-closed. Para testar Android: `gradlew.bat testDebugUnitTest assembleDebug`. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`. Instale por USB com `adb install -r app/build/outputs/apk/debug/app-debug.apk` ou copie o arquivo para o celular. Configure propriedades e backend OIDC antes de testar login e dados reais; sem isso, o login permanece desabilitado. A versao de depuracao usa chave de debug e nao serve para distribuicao de producao.
