# Instalação administrativa — ROTA GRM

**Preparação, não implantação executada.** Usar os projetos Supabase, Firebase e serviço Render existentes, após confirmar propriedade e autorização. Não criar serviço duplicado, habilitar plano pago ou aplicar SQL remoto automaticamente.

## 1. Segurança antes de conectar

- Revogar/substituir os tokens encontrados no `env` e logs originais. Não reutilizar valores expostos.
- Segredos: token GRM, URI PostgreSQL com senha, credencial administrativa Firebase e keystore/senhas de assinatura. Inserir somente em formulários seguros/painel de ambiente; não enviar pelo chat, commitar ou copiar para Kotlin/JavaScript.
- URL GRM, issuer, client ID OAuth e publishable key são públicos; lista de usuários autorizados continua informação administrativa.
- Não registrar URLs GRM completas: o contrato envia token em query string. Cliente não segue redirecionamentos; erros brutos são suprimidos. Evitar logs de debug HTTP/APM que capturem query ou bearer.
- Manter backup e revisar migrations antes de aplicação. O código não altera registros oficiais da GRM.

## 2. Supabase: Auth e banco

1. Confirmar projeto e domínio público. Referência recebida: `https://byjmnvnojwssjswyeybj.supabase.co`; não foi testada remotamente.
2. Habilitar/configurar OAuth Server com assinatura assimétrica compatível RS256/ES256. Registrar cliente **Public**, Authorization Code + PKCE, redirect exato `com.grm.rotagrm:/oauth2redirect`, sem client secret no Android.
3. Configurar a página de autorização `/oauth/consent` no domínio HTTPS real do backend e conferir discovery. Scopes Android: `openid email profile`. Não inventar scope GRM.
4. Criar contas autorizadas, confirmar emails e preencher `OIDC_ALLOWED_EMAILS` no backend. Não exigir `email_verified` no access JWT: o backend verifica `/auth/v1/user` e `email_confirmed_at`.
5. Confirmar que access tokens OAuth contêm `client_id`, `session_id`, `sub`, `role=authenticated`, issuer e audience esperados. O código rejeita ID token/cliente divergente e sessão ausente/encerrada.
6. Conferir estado das migrations no painel; **não reaplicar as duas existentes se já aplicadas**. Ordem:
   - `20261023162717_create_operational_history.sql`
   - `20261023162855_index_snapshot_record_references.sql`
   - `20261103130000_secure_shared_snapshots.sql` (nova)
7. Validar schema privado `operational`, RLS habilitado, sem privilégios para `public`, `anon`, `authenticated`. A role do backend deve ter acesso administrativo controlado às tabelas operacionais e SELECT em `auth.sessions`/`auth.users`; roles públicas não recebem esse acesso. Com RLS sem políticas públicas, o acesso servidor depende de role apropriada/owner/BYPASSRLS. Não usar senha do banco ou service-role no APK/página OAuth.
8. Obter URI de conexão no painel Connect. Usar host e modo direto/session pooler apropriados ao ambiente; não alterar hostname para contornar certificado. `prepare_threshold=None` está definido, mas compatibilidade do pooler e dos locks transacionais precisa de validação remota.
9. Baixar CA do projeto em Database/SSL settings e montar arquivo do servidor. `PGSSLROOTCERT` deve apontar para ele. O backend exige `sslmode=verify-full`: criptografia **e** validação de CA/hostname. Não reduzir para `require`/`disable` para “fazer funcionar”.

**Sessões:** o backend verifica `auth.sessions`, `not_after` e sua lista privada de revogação. Logout grava revogação privada e desabilita dispositivos antes de solicitar logout local ao Supabase. Não altera diretamente tabelas de Auth. Sessão remota removida/expirada impede leituras e futuras entregas autorizadas.

## 3. Backend/Render existente

1. Revisar código no repositório autorizado e o serviço existente. `render.yaml` mantém o identificador de plano recebido `0.5c-512mb`, mas não é autorização de gasto. Confirmar preço e orçamento no painel antes de criar/alterar serviço.
2. Variáveis obrigatórias: `URL_API_GRM`, `TOKEN_API_APONTAMENTOS_GRM_PUSH`, `SUPABASE_DB_URL`, `PGSSLROOTCERT`, `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `OIDC_ISSUER`, `OIDC_AUDIENCE`, `OIDC_CLIENT_ID`, `OIDC_ALLOWED_EMAILS`.
3. Contrato GRM: GET `https://dr-db.grm.valeglobal.net/api/v2/status-log-base-report`, parâmetros originais `token`, `begin`, `end`, janela do início local do dia anterior até agora. Não há POST GRM nem filtro inventado. Corpo vazio/inválido não substitui snapshot; lista vazia JSON válida é uma confirmação de zero registros.
4. Montar CA como secret file `/etc/secrets/supabase-ca.crt` ou ajustar `PGSSLROOTCERT` para o caminho real. Não existe certificado fabricado no pacote.
5. Configurar intervalos: `SNAPSHOT_INTERVAL_SECONDS=60`, `SNAPSHOT_STALE_SECONDS=120`, `SNAPSHOT_RETENTION_DAYS=7`. Em intervalos maiores, escolher stale coerente, normalmente pelo menos duas vezes o ciclo; o cliente continua conservador com seus timers.
6. `OPERATING_KPI_CATEGORIES` e `STOPPED_KPI_CATEGORIES`: listas exatas confirmadas pelo responsável, separadas por vírgula. Deixar vazias até confirmação. Sobreposição bloqueia a configuração; ausência de apontamento não significa parado/operando.
7. Docker inicia Uvicorn não-root, proxy headers habilitados e access logs desativados. HTTPS termina no proxy Render. `FORWARDED_ALLOW_IPS=*` só é apropriado com **ingresso exclusivo pelo proxy confiável**; em outro host, usar IPs específicos e bloquear acesso direto. Confiar em header enviado diretamente por cliente permitiria forjar HTTPS. Verificar a topologia real antes de expor serviço.
8. `/healthz` verifica processo, não prontidão operacional. Testar `/v1/readiness` com bearer autorizado; 503 é esperado sem snapshot fresco. `/v1/snapshot` lê a versão compartilhada sem consulta GRM por aparelho.

Se o banco falhar, não há consulta GRM sem coordenação: preserva-se cache em memória como stale. Se GRM falhar, tentativa é registrada e último payload válido permanece. Cada ciclo faz até três tentativas transitórias, não três consultas por telefone. Mais de uma instância usa locks transacionais PostgreSQL; não há promessa de execução exatamente uma vez diante de crash antes de commit.

**Retenção:** payloads de snapshots antigos são removidos após o período configurado; linhas sem referências podem ser apagadas. Histórico mais recente de cada ID em `operational.records` permanece, portanto sete dias não é retenção global de todos os dados. Outbox enviada/cancelada é removida depois de 30 dias durante polls bem-sucedidos. Dispositivos sem atividade por 30 dias deixam de ser elegíveis, mas limpeza de usuários/sessões/tokens históricos deve seguir política administrativa própria.

## 4. Firebase opcional

- Padrão servidor `FCM_ENABLED=false`; Android `rotaFcmEnabled=false`.
- Usar projeto Firebase real e app Android com package `com.grm.rotagrm`; obter `google-services.json` do painel e colocá-lo em `android/app/` somente no ambiente de build.
- Credencial administrativa: `FCM_SERVICE_ACCOUNT_JSON` em secret de ambiente **ou** `GOOGLE_APPLICATION_CREDENTIALS` apontando para secret file no servidor. Não incluir nenhuma dessas credenciais no APK ou ZIP.
- Habilitar FCM nos dois lados após revisar destinatários/permissões. Worker registra token mediante sessão validada, sincroniza preferências e desabilita registro quando não há permissão.
- Fila privada usa chave estável por dispositivo/evento e revalida usuário, allowlist e sessão antes de enviar. Tokens rejeitados como não registrados são desabilitados; falhas transitórias têm até cinco tentativas na fila.
- Payload data-only de prioridade alta contém apenas `event_id`, `alert_id`, `session_tag`, `sound`. Texto local é genérico; nenhuma informação de operador/equipamento/status entra na notificação.
- Primeiro snapshot de manutenção é baseline, sem flood de avisos antigos. Regra original inclui manutenções abertas e as iniciadas no dia; manutenção encerrada mas ainda elegível no dia não é cancelada só por deixar de ser o apontamento atual.
- FCM envia um aviso por evento/limite; repetição configurável ocorre localmente enquanto o aplicativo consulta em primeiro plano. Não foi implementada promessa de repetição periódica push com aplicativo fechado. A fila é at-least-once, não exatamente uma vez: Android deduplica hashes. Após 30 dias/limite de retenção, dedup não é eterno.
- Plano Render gratuito pode suspender por falta de tráfego. Loop interno não garante servidor sempre ativo. FCM depende de rede, Android, permissão, bateria e disponibilidade; **force-stop exige reabertura**, não há garantia de entrega após encerramento forçado.

## 5. Android e distribuição externa

Seguir [Android README](../android/README.md). Configurar URLs/client ID públicos uma vez por build; fornecer keystore por ambiente seguro. Executar testes/build/assinatura em máquina externa. Validar APK novo e manter chave compatível com distribuição existente. Não existe APK atualizado nesta entrega.

## Referências oficiais consultadas

Consultadas na revisão desta entrega em 06/11/2026; documentação não prova configuração remota:

- Supabase: [OAuth flows](https://supabase.com/docs/guides/auth/oauth-server/oauth-flows), [Token security](https://supabase.com/docs/guides/auth/oauth-server/token-security), [SSL enforcement](https://supabase.com/docs/guides/platform/ssl-enforcement), [PostgreSQL connection](https://supabase.com/docs/guides/database/connecting-to-postgres).
- Uvicorn: [Proxy settings](https://uvicorn.dev/settings/); FastAPI: [Behind a proxy](https://fastapi.tiangolo.com/advanced/behind-a-proxy/).
- Render: [Deploy FastAPI](https://render.com/docs/deploy-fastapi), [Compute plans](https://render.com/docs/compute-plans), [Free services](https://render.com/docs/free).
- Firebase: [Receive Android messages](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), [Message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority).