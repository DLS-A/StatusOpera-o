# ROTA GRM — Android existente

Kotlin/Jetpack Compose/Material 3 preservados. Fontes versão `0.2.0` (`versionCode=2`), sem APK compilado nesta execução. O módulo duplicado do projeto original foi mantido apenas no original; use `android/` como canônico.

## Configuração administrativa por build

Editar `rota-public.properties` nesta pasta com URL HTTPS real do backend, issuer, client ID público e audience. Os valores também podem ser sobrescritos por propriedades Gradle `-P...`. Não é necessário configurar API por telefone. Não preencher segredo de cliente OAuth: o cliente Android é público e usa PKCE.

```properties
rotaApiBaseUrl=https://DOMINIO_REAL_DO_BACKEND
rotaOidcIssuer=https://byjmnvnojwssjswyeybj.supabase.co/auth/v1
rotaOidcClientId=IDENTIFICADOR_PUBLICO_REAL
rotaOidcAudience=authenticated
rotaFcmEnabled=false
```

Os marcadores acima são explicativos, não configuração pronta. O arquivo entregue mantém API/client ID vazios e login desabilitado até configuração real. Redirect cadastrado no Supabase: `com.grm.rotagrm:/oauth2redirect`.

FCM opcional exige projeto Firebase Android real, package `com.grm.rotagrm`, `app/google-services.json` obtido no painel e `rotaFcmEnabled=true`; não foi fornecido JSON inventado. Esse arquivo público de configuração deve ser tratado conforme política do projeto e está ignorado pelo Git; a credencial administrativa FCM é secreta e fica somente no servidor.

## Compilação externa e assinatura

Usar Android Studio/SDK/JDK compatíveis com as versões declaradas nos arquivos Gradle existentes. Em máquina externa, executar `./gradlew testDebugUnitTest assembleDebug` (Windows: `gradlew.bat ...`). Neste sandbox não foi executado Gradle nem compilado APK. Parsing de fonte não valida resolução das dependências, tipos, manifest merger ou interface.

Para release, fornecer no ambiente seguro:

- `ROTA_SIGNING_STORE_FILE`
- `ROTA_SIGNING_STORE_PASSWORD`
- `ROTA_SIGNING_KEY_ALIAS`
- `ROTA_SIGNING_KEY_PASSWORD`

Depois: `./gradlew testDebugUnitTest assembleRelease`. A guarda de assemble/bundle release exige URLs HTTPS, cliente/audience e keystore reais; não substitui revisão de segurança nem assinatura verificada. Guardar chave e backup fora do código. Não trocar a chave de um app já instalado sem avaliar o caminho de atualização. Distribuir internamente apenas o APK novo, assinado e validado, nunca o APK antigo recebido.

## Uso e limites

Cinco abas, busca/filtros/detalhes, preferências locais, alerta genérico e cache cifrado. Atualização automática somente com Activity RESUMED; manual disponível na tela. Datas apresentadas em `America/Sao_Paulo`. Durações de snapshot stale ficam congeladas até nova confirmação. Cache offline requer identidade cifrada anteriormente validada e access token ainda não expirado; não dá acesso ilimitado após expiração/revogação.

FCM é entrega genérica opcional sem campos operacionais; não executa polling de GRM no telefone. O servidor verifica sessão antes de enviar; o Android aceita somente o hash da sessão cifrada corrente. Mensagens já em trânsito podem chegar depois de mudanças remotas. Logout local limpa identidade, cache, notificações e registro local; logout remoto deve ser confirmado pela resposta do servidor.

[Instalação](../docs/instalacao.md) · [Uso](../docs/uso.md) · [Validação](../docs/validacao.md)
