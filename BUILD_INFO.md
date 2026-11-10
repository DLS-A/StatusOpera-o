# Status Operacional — build de teste 0.4.0-test

## Atualização operacional — 10/11/2026
- URL real https://status-operacional-api.onrender.com configurada; backend free publicado e validado com GRM/Supabase reais.
- Ativação única por código compartilhado; credenciais individuais cifradas por telefone; sem e-mail/login/OAuth no APK; código apenas em entrega privada.
- versionCode 4; tema sem barra Android duplicada; FCM desabilitado.
- 67 testes Python/integração PostgreSQL local aprovados; smoke remoto de ativação/snapshot/readiness/revogação individual aprovado.
- Workflow externo agora compila checkout da branch isolada, não ZIP antigo. Execução 42823754826 em compilação na última verificação.
- Leia docs/ativacao.md; as informações abaixo registram a build histórica 0.3.0-test e NÃO descrevem a configuração atual.

## Histórico: 0.3.0-test

## O que foi aplicado
- Nome visível Status Operacional; azul #2E3192 e branco.
- Cinco abas com pictogramas nativos, resumo em painéis, cartões contornados, duração/detalhes e ajustes agrupados.
- Equipamentos mantém pesquisa e lista; removidos Todos, Com status e Sem status.
- Fonte principal da UI: android/app/src/main/kotlin/com/grm/rotagrm/ui/StatusOperationalScreen.kt.
- Sem login, apenas a estrutura vazia e os ajustes locais são acessíveis; snapshot/alertas são explicitamente ocultados. Nenhum dado fictício ou bypass de Auth.
- applicationId, namespace, redirect e aliases de Keystore preservados.

## Bloqueio de dados reais
Repositório e administrador não forneceram URL HTTPS do backend publicado ou client ID público OAuth. Campos permanecem vazios em android/rota-public.properties. Login/consulta reais ficam desabilitados; o aviso aparece no aplicativo. Conhecer o endpoint GRM não fornece um backend autorizado para o Android.

O APK debug permite testar instalação, navegação, visual e preferências locais. Não é release homologado, nem comprovação de conexão GRM/Supabase. FCM continua desabilitado.

## Build externo
- Somente GitHub Actions no repositório público autorizado DLS-A/StatusOpera-o, branch status-operacional-apk-2026-11-09.
- main e original preservados. Nenhum deploy, SQL remoto ou mudança de plano.
- Fontes sanitizados baixados por URL pública com SHA-256 fixado no workflow. O workflow, não main, usa este pacote revisado; não compila a versão antiga de main.
- Toolchain alinhada para build reprodutível: AGP8.13.2, Kotlin Android/Compose2.2.21, Gradle8.13, SDK36, Java17. minSdk26 e targetSdk36 mantidos.
- Dependências declaradas usam versões estáveis disponíveis no Maven, verificadas antes do build. Não foi instalado/rodado SDK ou Gradle no sandbox.
- versionCode3 e versionName0.3.0-test.
- APK assinado com chave debug gerada no runner. Essa assinatura pode não atualizar a instalação anterior; teste num aparelho separado. Não remover app produtivo sem avaliar perda de dados.

## Evidência e limites
- 60 testes Python locais aprovados novamente; PostgreSQL local descartável e Auth simulado, sem serviços reais.
- Parsing local passou: 23 Python, 20 Kotlin/Gradle, 6 XML, 3 JSON e 1 JavaScript. Não é compilação.
- Testes JUnit/build/verificação de APK devem ser comprovados pela execução GitHub Actions, não por este arquivo.
- Sem teste em aparelho ou confirmação de UI renderizada. Sem backend/OAuth real, TLS remoto, GRM/FCM real.
- docs/change-manifest.json e documentos anteriores pertencem à base histórica de 06/11. docs/apk-source-manifest.json identifica esta cópia de build e prevalece para hashes dos fontes atuais.

## Próxima build operacional
Configurar URL/backend/client OAuth reais em uma cópia autorizada, homologar login/banco/TLS/GRM e só então assinar release com a chave mantida pelo responsável. Nunca adicionar token GRM, senha PostgreSQL ou credencial administrativa Firebase ao Android.