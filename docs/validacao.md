# Validação — fontes, não operação homologada

## Evidência local

O resultado final está em `test-results.txt`. Suítes explícitas: datas/regras GRM, HTTP ASGI, JWT assinado com provider simulado, resiliência/snapshots/FCM simulado e integração PostgreSQL 17 local descartável. Nenhuma credencial recebida nem host GRM/Supabase/Firebase real é necessário nesses testes.

TestClient usa URL lógica HTTPS; **não estabelece TLS**. JWT é assinado localmente, mas discovery/user/logout remoto continuam simulados. Mensagem Firebase é construída e o envio é mockado, sem push real. SQL/migrations e concorrência foram exercitados em PostgreSQL real **local**, com tabelas Auth de teste — não na instalação Supabase.

Parsing estático verifica sintaxe de `.kt`/`.kts`, Python AST, JavaScript e XML. Não valida tipos Kotlin, versões resolvidas Gradle, recursos, manifest merger, lifecycle, Keystore, Compose, acessibilidade, som ou aparelho. Os nove testes JUnit presentes nas fontes Android devem ser executados externamente; não foram executados neste sandbox.

O teste HTTP original que dependia de persistência não isolada foi substituído por isolamento explícito; a persistência agora tem suíte separada com banco local. Baseline auditado: 12 testes, 11 aprovados, 1 erro de isolamento. Não comparar aprovação nova como prova de produção.

## Executar novamente

Usar ambiente isolado, sem `.env` de produção. Instalar `backend/requirements-test.txt`. As versões backend diretas estão fixadas às usadas na suíte local; dependências transitivas não possuem lock completo. Revalidar instalação no Python da imagem Docker antes de implantação.

```sh
python -m unittest -v test_datetime_grm test_backend_snapshot test_security_services
```

PostgreSQL opcional: criar banco local descartável `rota_grm_local_test`, owner com permissão de schema e criação das roles de teste `anon`/`authenticated`; conexão usa socket `/var/run/postgresql`. **Não apontar para Supabase**. A suíte remove/recria somente schemas `operational`/`auth` desse banco fixo, por isso exige opt-in:

```sh
RUN_LOCAL_POSTGRES=1 python -m unittest -v test_postgres_integration
```

Verificador de sintaxe: `PYTHONPATH=... python scripts/verify_sources.py` após instalar dependências de parsing. Ele não compila Android.

## Checklist externo obrigatório

| Item | Critério | Estado desta entrega |
|---|---|---|
| Credenciais expostas | Rotação/revogação confirmada pelo responsável | Pendente |
| Banco Supabase | Migrations corretas, RLS/revokes, role servidor, CA/hostname, auth.sessions real | Pendente |
| HTTPS | Cadeia válida, proxy confiável, rejeição de HTTP/bearer sem redirect | Pendente |
| OAuth real | Discovery/PKCE/redirect, JWT cliente correto, email confirmado/allowlist | Pendente |
| Negação de acesso | Usuário não autorizado, expirado, revogado, banco/provedor indisponível | Pendente remoto; coberto localmente |
| GRM real | GET/janela/parâmetros autorizados, unidade de timestamp, formato e registros antigos | Pendente |
| Backend real | Um ciclo compartilhado, persistência/restart, stale/recovery, readiness | Pendente remoto; coberto localmente |
| Android build | Dependências, type-checking, JUnit, APK novo assinado externamente | Pendente |
| Android UI | Cinco abas, pesquisa/filtros, tempos/fuso, banner em falha, manual/automático | Pendente aparelho |
| Cache/sessão | Restart, offline até expiração, troca usuário, 401/403, logout durante request/refresh | Pendente aparelho |
| FCM | Projeto, registro/token rotation, permissão, background/Doze, dedup local/push, logout | Pendente real |
| Limites Android | Force-stop, bateria/rede, canal/silencioso, retenção de hashes | Pendente aparelho; sem promessa de garantia |
| Publicação/custo | Serviço existente, orçamento autorizado, ingressos/secrets e rollback | Não executado |

## Segurança de entrega

O ZIP deve excluir `env`, logs originais, dumps/cache operacionais, APK/AAB, builds, keystores, credenciais Firebase e o módulo Android duplicado. Manifestos SHA-256 permitem conferir original e alterações. A varredura procura os valores privados originais sem divulgá-los; isso não é certificação contra todo segredo possível nem substitui revisão humana. Não reenviar o ZIP original inseguro como pacote atualizado.

Só declarar “pronto para operação” depois de cumprir o checklist externo com evidências do ambiente real.