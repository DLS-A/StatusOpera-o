# Status Operacional — execução aprovada

## 0.6.3 — LIBERADO PELA MANUT no Início
- Nome confirmado pelo usuário. Incluir todos os equipamentos cujo apontamento atual corresponde exatamente ao motivo normalizado.
- Projeção homeAlerts inclui cartões atuais sem depender de limites/AlertEvaluator ou eventos de manutenção. Mesmo formato AG; descrição Liberado pela manutenção, tempo total e abertura. Contagem usa lista completa e sem duplicatas.
- Som/monitor/ViewModel/backend/aba Manutenções preservados. Registros liberados permanecem em Equipamentos e desaparecem do Início após mudança confirmada. Cache/stale sinalizado, sem novo tempo inventado.
- Versão0.6.3/code9. Três novos testes escritos; publicar branch isolada e compilar via Actions; pendências do backend0.6.2 não resolvidas por esta alteração visual.
- Primeiro build43240646604 falhou no type-check: argumentoLongextraemOperationalAlert. Corrigido com argumentos nomeados; novo build requerido.88Python OK. Snapshot público12/11 14:46Z agorafreshcomcontratonovo;classificações e durações conferidas;CB-301 LIBERADO atual. Implantação anterior chegou ao payload, mas commitRender não inspecionado.

## Ajustes 0.6.2 — 11/11/2026, em validação
- Validação final local:88testes Python/PostgreSQL OK;32JUnit Android OK, Actions43023217385 SUCCESS, artefato13856047009. APK0.6.2/code8 SHA256a1e330584151048b5de23a46715bd92778e1681c76684b9694f7245fc29c6862,14.817.635bytes;v2 validada localmente, certificado diferente0.6.1.
- Fonte APKd054ea9f24f3fa6b2f654e03124748800ed76002;fonte backendba7fa90f8e61e2efd37bd85a09dec88d3a5ca000 (Android idêntico).32JUnit incluem10novos testes executados;DEX símbolos/textos verificados. main remota preservada.
- Fonte GRM real12:48:58Z:93atuais,21operating/60stopped/12maintenance;20fallbackWeb,55locais. Todas durações=end-início, medição=endDate, somenteOperationalDowntime=stopped. Bootstrap com histórico persistido2.59s;EH309 abertura24/09confirmada.
- BLOQUEIO:API administrativaRender(GETservices/env/deploys/owners/logs autenticados) passou a retornarReadTimeout. Um POSTdeploy ba7fa90... tambémReadTimeout:resultado desconhecido, não repetirPOSTseminspecionar. Arquivo062-deploy-post-unverified.json registra isso. Snapshot público12:53:59Z seguefresh,contratonovoaindaausente. NÃO afirmar backendnovoemprodução nemcorreçãofinaldos6itens.
- EntregaAPKpode serpreliminar comalerta explícitodebackendpendente;instruirRenderManualDeploy→Deploylatestcommitnabranchexistente,semalterarplano/segredos/auth/FCM. Verificardeploy+snapshotnovofrescodepois. Semtesteemaparelho,semgarantiadeuptimefree.
- Senha local de Ajustes30824 (somente digest no código), bloqueia ao sair da aba/ON_STOP; demais abas livres.
- Classificação por statusType GRM: Operating=operação, Operational Downtime=parado. HAO/HMC são ambíguos; manutenção planejada/não planejada separada; ausente=desconhecido.
- Autor completo, fallback Apontado via Web e local apenas preenchido. Início mostra duração total em aberto de REFEIÇÃO e AG MANUTENÇÃO; filtro de apresentação preservado.
- API fornece instante de medição da duração; cliente usa relógio servidor e delta monotônico, sem depender do fuso/alterações do relógio do celular. Cache/stale mantém duração do relatório, sem inventar evolução offline.
- Sexto pedido: investigar parada da atualização. Evidência11/11: shutdown12:16:53Z, startup12:22:51Z; coletas entre12:16:09Z e12:22:51Z interrompidas. Agora API200/fresh às12:41:37Z. Não inferir causa exata do encerramento nem prometer uptime em free.
- Reinício reaproveita histórico compacto privado persistido, sem nova conversão de fuso; reduz bootstrap91dias repetido. Loop passa a descontar duração da consulta do intervalo. APK limita tentativa completa a90s; rotina tenta novamente sem apagar cache.
- Novas regressões Python/JUnit escritas; primeira suíte87testes encontrou fixture obsoleta que classificava pela sigla configurada; fixture adaptada ao novo contrato. Reexecutar suíte e compilar externamente antes de qualquer entrega.

## Próxima correção — requisitos confirmados
- Firebase recusado posteriormente: usuário não tem projeto e pediu outra forma usando sistema atual. Implementado monitor dataSync iniciado com Activity visível, notificação fixa, mesmo engine Application-scoped, apoio WorkManager15min. Respeita timeout Android15/START_NOT_STICKY; não usa specialUse nem exact alarms. Som/canal Android expostos com teste manual, sem contornar DND.
- Ajustes devem manter escolha manual dos limites, apenas pausa ergonômica e refeição; eliminar duplicação por acento e controles de manutenção.
- Exemplos fornecidos: CB-312 começou em 04/11 (contexto 2026); CB-344 em AG MANUTENCAO. Investigar histórico real, sem inventar data de abertura.
- Remover reconhecimento; alertas permanecem enquanto apontamento atual persiste e são reconciliados quando atualização válida confirma mudança.
- Solicitação adicional: aba Equipamentos exclui os quatro tipos de manutenção; usa lista completa internamente para alertas e retorna automaticamente após mudança em snapshot válido.
- GRM real confirma fragmentação por turno de MESMO ID. CB-312 id52876935 aberto04/11/2026 06:17:27 São Paulo; CB-344 novo AG id53075977 aberto09/11/2026 05:04:00 após DISPONIVEL, não mesclar com AG anterior id53014478.
- Backend reconstrói apenas fatias contíguas por ID/tag/motivo, confirma abertura por predecessor distinto, sinaliza startConfirmed false quando falta histórico. Backfill em blocos7dias (máximo28), cache/hora, limite10MB/chamada; falha histórica não elimina atual. Sem modificar timestamps brutos nem inventar datas.
- 78 testes Python/PostgreSQL aprovados e verificação em relatório real salva confirmou exemplos. 8 novas regressões JUnit escritas (total esperado20), ainda não compiladas.
- Versão0.6.0-test em fontes. Próximo: publicar branch isolada, GitHub Actions, implantar backend, verificar entrega. Não prometer disponibilidade contínua em Render free, som após force-stop ou homologação física.
- Concluído: commitfinal ebf8e2b8456a23cfbfd42bfbdd2a3c7072a9dc72; main remoto verificado preservado. 79 testes Python/PostgreSQL aprovados, incluindo cache de histórico limitado a fatias atuais/predecessores, sem acumular cortes móveis nem armazenar28dias completos a cada minuto.
- GitHub Actions42855553943 SUCCESS, artefato13783495506,20JUnit aprovados. APK14.801.247bytes, v2 válida, SHA25608af8803b8be0ee46850a8720f0c8a7fd9c7bf5289db3ebe889bddd3d75aec1a. DEX contém monitor/Worker/Application e URL real; reconhecimento e manutenção iniciada hoje ausentes. Manifest badging confirma versão6 e permissõesFGS/dataSync.
- Render final dep-dbpkeb0u01pc73dk33bg LIVE, free, snapshotfresh93equipamentos/15manutenções/0pausas. CB312 início04/11 06:17:27, CB344 AG início09/11 05:04:00, startConfirmed true. Snapshot/readiness200 sem bearer, escrita snapshot405, ativação404 para payload válido, dispositivo401. Falha inicial de smoke: stale durante troca de deploy; segunda: payload de ativação curto retornou422; ambos diagnosticados e smoke válido passou.
- Entrega0.6 preparada e revisada: APK, instalacao.report/content.md, evidênciasZIP. Assinatura debug diferente0.5: anotar ajustes e remover somente APK teste anterior; sem teste físico ou pausa real aberta. Próximo: deliver.

## Correção posterior aprovada — acesso livre
- Usuário pediu remover autenticação e corrigir apontamento atual, quatro manutenções e pausas/alertas.
- Tipos: AG MANUTENCAO, EXEC MANUT CORRETIVA, EXEC MANUT PREVENTIVA e EXEC MANUT PREVENTIVA-250 HRS; API usa também espaços ao redor do hífen. Exemplos CB-303/CB-310.
- Diagnóstico real: 1578 registros, endDate preenchido em todos; 93 máquinas com corte final comum móvel. Duas consultas confirmaram mesmo ID/início com endDate e logDuration avançando para os exemplos. A regra anterior endDate=null era incompatível com esse relatório.
- Adaptador report_contract.py usa o corte comum e preserva suporte explícito null/zero; não usa simplesmente último histórico por máquina. Quatro nomes normalizados; pausa ergonômica exata com acento aceito.
- Android inicia diretamente, sem formulário/código/bearer nem consulta de sessão remota; cache local cifrado mantido. Durações usam intervalo do relatório, evitando inflação de 3h por diferença de relógio/fuso. Pausas também alimentam AlertEvaluator.
- 72 testes Python/PostgreSQL local aprovados; três novos testes Android (total12) para duração, pausa/alertas e stale.
- Render AUTH_MODE public; hash de ativação removido, segredos preservados; deploy dep-dbpiuvbm8hqs73e23cug live no plano free.
- Verificação pública: 200 sem bearer; 93 apontamentos atuais, 16 manutenções, zero pausas atuais; CB-303 corretiva e CB-310 preventiva nas duas listas. Ativação404, escrita snapshot405, escrita dispositivo401.
- APK 0.5.0-test/versionCode5. Primeiro build 42838338861 em curso (e372f9634d7715e40582d306a58ca5dcb81b333e); houve ajuste final de textos/README depois.
- HEAD final 73063e14cef6cbc89a733b5be450c3cd21fb0c93; execução final 42838902064, etapa de upload do artefato na última consulta. Primeiro build 42838338861 concluído success, mas não é o build final. workflow_dispatch retornou403 por permissão (não repetir); trigger por alteração do workflow funciona.
- Execução final 42838902064 SUCCESS; artefato13774919035 baixado, SHA do ZIP confirmado. APK0.5.0-test 14.784.763 bytes, SHA2566c58c33469f13911c5f95393cd20d60a7dc89fb6d72082a27cbf2a08b21152d0, assinatura v2 válida, 12JUnit aprovados.
- DEX real validado: URL correta e cabeçalho acesso livre presentes; campos/botões de ativação/login ausentes. Certificado diferente do APK0.4: desinstalar somente a versão de teste anterior.
- Revalidação remota final:93 atuais/16 manutenções, CB-303/CB-310 nas listas, zero pausas atuais, sem bearer200, ativação404 e escritas bloqueadas.
- Entrega preparada em /home/user/status-operacional-apk-entrega/0.5.0-test: APK, instalacao.report/content.md sem código, ZIP de evidências. Próximo: deliver desses arquivos.
- Sem teste físico/emulador; sem pausa real aberta para teste real de notificação. Pausa futura validada via regressões, não fabricar operação real.

## Histórico da versão anterior (abaixo)

## Escopo
- Aprovação: “Sim, execute as alterações do plano”. Continuação em cópia separada.
- Preservar original; Kotlin/Compose/Material 3, FastAPI, Supabase e Render.
- Escopo posterior autorizado: implantação Render gratuito/Supabase pfhlljjqltwqfbfbalpw, APK externo e ativação única por código compartilhado; sem e-mail/login.

## Verificado
- Baseline: 12 testes, 11 aprovados, 1 erro de isolamento de persistência.
- Implementação backend/Android escrita e revisada; entrega de fontes preparada.
- 60 testes Python aprovados: 8 datas, 16 HTTP, 26 segurança/serviços, 10 PostgreSQL local.
- PostgreSQL 17 local instalado para testes descartáveis, sem conexão externa.
- Parsing de 19 arquivos Kotlin/Gradle sem erros; não é compilação/type-checking.
- Integridade de 89 entradas do ZIP original e 54 hashes de fontes preservados.
- Varredura sem correspondência de valores privados conhecidos; sem APK/logs/dumps/keystore.

## Estado atual verificado — 10/11/2026
- Código de ativação aleatório de 100 bits, apenas hash no servidor; credenciais individuais por telefone, hashes no banco e AES-GCM/Keystore no Android.
- Throttling persistente, revogação individual, código mascarado e não salvo; confirmação de desativação.
- 67 testes Python/PostgreSQL local aprovados, incluindo ativação e persistência dos limites.
- Migração 20261110010000 aplicada e registrada; papel runtime sem superuser/acesso a auth; TLS verify-full.
- GRM real normalizado: 1493 registros, 93 equipamentos na validação.
- Render srv-dbphk2gm7kps73avbgkg, plano free, https://status-operacional-api.onrender.com; deploy dep-dbphk30m7kps73avbimg live.
- Smoke remoto: health 200; sem bearer 401; código errado 403; sessão/snapshot/readiness 200; dados frescos e persistidos; revogado 401 e outro telefone 200. Telefones de teste removidos.
- OAuth Supabase desabilitado; APK sem AppAuth/redirect; FCM false.
- Commit 242a172c71f4f69142f318db4ac761cbefee91d1 na branch isolada; main preservada. Workflow compila checkout atual, não ZIP antigo.
- GitHub Actions 42823754826 concluído com sucesso, artefato 13768735256 baixado; digest ZIP confirmado.
- APK 0.4.0-test: 14.801.147 bytes; SHA256 024dbee93ef4a89bfa218be365ad2387abaebef1f7746da1e7a096249d304cb3. Integridade ZIP/manifest/dex/resources, URL real no DEX e ausência de segredos conhecidos verificadas.
- Assinatura v2 válida, debug RSA2048; certificado diferente da build anterior, portanto precisa desinstalar a versão de teste antiga antes de instalar. 9 JUnit aprovados, sem falhas/erros/ignorados.

## Próximos passos
1. Entregar APK e instruções privadas com código, sem publicar código ou segredos.
2. Informar necessidade de remover APK debug anterior para instalar nova assinatura. Sem teste físico/emulador nesta execução.

## Limites
- Render gratuito pode suspender por inatividade; não prometer disponibilidade contínua.
- Sem push com app fechado; alertas locais durante uso.
- Não inferir classificação KPI sem configuração administrativa.
- Segredos somente no diretório administrativo privado, fora dos fontes/repositório/artefatos públicos.

## Ajuste de Início — 0.6.1-test (10/11/2026)
- Pedido mais recente: retirar pausa do Início; manter somente REFEIÇÃO e AG MANUTENÇÃO.
- Filtro homeAlerts e regressão atualizados; contador da tela já usa a mesma lista filtrada.
- A mudança é apenas de apresentação: monitor, som, Ajustes e aba Manutenções não foram alterados neste ajuste.
- Fontes locais atualizados. Compilação/JUnit e APK 0.6.1-test ainda pendentes; não confundir com a versão 0.6.0-test anteriormente entregue.
- Em 10/11, usuário pediu testes e APK pronto. Branch isolada publicada no commit af0d14065148472391b73323b3efd767c663c6e5; Actions 42859552874 iniciado. main verificada e preservada.
- 79 testes Python/PostgreSQL passaram novamente. Backend existente live/free, snapshot fresco com 93 equipamentos/15 manutenções, snapshot/readiness 200; escrita snapshot 405, ativação 404, escrita devices 401. Datas CB-312 e CB-344 mantidas e confirmadas.
- Diff comprova backend, ViewModel e notificações inalterados. Não redeployar backend para ajuste visual. Empacotador package_home_061.py preparado; aguardando resultado do build externo para validar APK/22 JUnit/assinatura e entregar.
- Resultado final: Actions 42859552874 concluído success; artefato 13785300257 baixado e digest GitHub validado. 22 JUnit passaram, incluindo as duas novas regressões de Início; nenhuma falha/erro/ignorado.
- APK 0.6.1-test/code7: 14.801.251 bytes; SHA256 0f34033834067ef699a25fa7da27f994b684879ecedf4cd85a9e3f9327d3d8b8. Integridade, ausência de segredos conhecidos e DEX com homeAlerts/isAwaitingMaintenanceStatus/texto novo verificados.
- Assinatura v2 verificada também localmente com apksigner; certificado diferente da 0.6.0. Instrução de instalação avisa anotar limites e remover somente versão de teste antiga, com perda de ajustes/cache locais.
- APK e guia prontos em /home/user/status-operacional-apk-entrega/0.6.1-test/. Continua sem teste físico/emulador; não prometer som/segundo plano sem homologação no aparelho.

## Correção das horas com imagens do usuário
- Referências: CB-351 04/11/2026 08:59:51; EH-309 24/09/2026 10:04:49; TE-309 preventiva250h 10/11/2026 09:18:22.
- Backend anterior mostrava CB-351 05:59:51 e TE-309 06:18:22 (−3h); EH-309 mostrava apenas primeiro trecho de26/10, abertura não confirmada. Raw GRM codifica relógio local em epochUTC para início e fim; converter ambos no adaptador, mantendo duração e ISO real genérico.
- Histórico de28dias insuficiente e respostas densas maiores que10MB cortavam backfill. Implementados91dias e subdivisão até1dia, com retenção compacta dos IDs atuais/predecessores.
- Consulta real do adaptador corrigido reconstruiu as três datas exatamente, startConfirmed true; bootstrap50.67s. Quatro testes adicionados (83 total passando). Falhas iniciais: predecessor de current descartado ao podar e fixture gerava intervalo negativo; corrigidos e suite novamente OK.
- Android/versão/APK sem mudança necessária; publicar correção backend, verificar snapshot em produção e informar atualização sem reinstalação. Deploy e relatório final pendentes.
- Final: commit38f2eeaa7302c61f7f2b43a60e7bbcb8194e4867 publicado na branch isolada; Render dep-dbpl7bek1f9s73fg4hgg LIVE/FREE. No bootstrap houve snapshot antigo/stale temporário; depois snapshot17:29:04Z fresco confirmou as três datas/horas exatas das imagens, startConfirmed true, durações=end-início e relógio endDate sem−3h. Snapshot/readiness200; escritas405/404/401. main preservada.
- Android inteiro idêntico ao commit do APK0.6.1 af0d140; correção chega com Atualizar, sem desinstalar/reinstalar. Relatório curto em /home/user/status-operacional-apk-entrega/correcao-horarios.report/content.md. Não houve teste físico; a validação remota é do payload recebido pelo app.
- Campo Total Parcial EH-309416:12:00 não equivale ao intervalo calendário24/09→10/11; período/filtro da captura não informado. Não alterar duração integral para esse número nem afirmar que o total parcial foi igualado. Se necessário, solicitar filtros para validar esse campo separadamente.
- Usuário confirmou período24/09/2026 10:04:49→10/11/2026 14:35:00 (corrigiu24/11 inicialmente digitado). Duração calendário correta4077011s =1132:30:11, não416:12:00; diferença716:18:11.
- Auditoria real de98fatias contíguas do mesmo apontamento EH-30950946660 cobre integralmente esse período, sem lacunas. API retornou corte corrente14:42:37 em vez de minuto solicitado14:35; o teste inicial que exigia fim exato falhou. Validação final recortou união de intervalos ao período confirmado e confirmou1132:30:11; não confundir corte retornado com período de auditoria.
- Servidor fresco17:43:11Z conserva abertura24/09 10:04:49 confirmada e duração corrente1132:38:07. Sem nova mudança de código/deploy/APK;416:12:00 continua sem critério de total parcial identificado, não é duração integral desse período. Evidência interna eh309-confirmed-period-audit.json.