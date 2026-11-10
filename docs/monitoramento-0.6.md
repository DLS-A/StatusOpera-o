# Status Operacional 0.6.1 — monitoramento sem Firebase

Este documento prevalece sobre instruções históricas de autenticação, reconhecimento e alertas apenas em primeiro plano. O acesso continua livre e somente leitura; nenhum segredo administrativo entra no APK.

## Regras
- Equipamentos exclui AG MANUTENCAO, EXEC MANUT CORRETIVA, EXEC MANUT PREVENTIVA e EXEC MANUT PREVENTIVA-250 HRS. Acentos e espaços no hífen são normalizados. A lista completa continua alimentando o monitor. Na atualização que confirma outro motivo, o equipamento retorna à aba.
- Manutenções contém os quatro motivos atuais, sem filtro de início hoje.
- Ajustes tem somente pausa ergonômica e refeição como motivos configuráveis. Mantém escolha manual, pausa padrão15min, refeição sem limite automático até configuração. Preferências antigas são canonicalizadas; limites de manutenção e reconhecimento não governam mais os alertas.
- Início é reconstruído apenas de fatias contíguas do mesmo ID/equipamento/motivo. Não é data da coleta ou do último turno. startConfirmed indica predecessor com outro ID. Sem histórico suficiente, UI indica abertura não confirmada e distingue primeiro trecho disponível. Nunca inventa data de abertura.
- Alertas levantados permanecem até um snapshot válido confirmar mudança de ID/motivo; alteração de limite não reconhece o alerta. Snapshot stale/offline não prova encerramento. Início mostra somente alertas de REFEIÇÃO e AG MANUTENÇÃO, com a contagem da mesma lista. Pausa e execução de manutenção não aparecem nos alertas de Início. A aba Manutenções mantém os quatro motivos. O filtro é somente visual: monitor e regras sonoras permanecem iguais, com som de manutenção somente para novos IDs observados após baseline.

## Segundo plano
Um motor por processo é compartilhado entre Activity, serviço e Worker, com mutex de consulta e deduplicação persistida. Ao abrir com permissão, inicia serviço dataSync visível por notificação fixa; é possível pausar/retomar em Ajustes. O serviço consulta o backend, não o GRM diretamente. Não foi usado specialUse para contornar limites do Android.

Android15+ limita dataSync em segundo plano a6h em24h; abrir o app em primeiro plano reinicia o orçamento conforme regra do sistema. onTimeout para o serviço prontamente, sem restart automático em background. WorkManager fornece apoio periódico com intervalo mínimo15min e rede disponível, sujeito a Doze, bateria e atrasos. O apoio pode voltar após reboot, mas não inicia um serviço dataSync pelo BOOT_COMPLETED.

Permissão não garante som: conferir volume de notificações, canal Alertas operacionais, Não perturbe, restrições de bateria e cooldown do Android. Ajustes oferece teste de notificação e acesso ao canal. Não alterar configurações Android à força. Forçar parada exige reabrir; não há garantia após force-stop nem SLA de entrega. Render free pode suspender: rede/serviço indisponível não produz alertas novos.

## Datas verificadas na origem em10/11/2026
- CB-312, ID52876935, EXEC MANUT CORRETIVA:04/11/2026 06:17:27, America/Sao_Paulo.
- CB-344, ID53075977, AG MANUTENCAO:09/11/2026 05:04:00. O AG anterior ID53014478 terminou antes de um apontamento DISPONIVEL PARA OPERAR; não foi unido ao novo.
- Consulta central normal permanece janela de um dia anterior. Histórico é recuperado em blocos7dias, até28dias, com limite10MB/chamada e cache em memória por hora para novos IDs; falha de backfill preserva atual e indica abertura não confirmada. IDs ainda ativos conservam suas fatias no cache quando a janela avança; reinício pode exigir backfill novamente.

## Validação
Testes Python/PostgreSQL local e regressões JUnit de configuração, refeição, filtragem e encerramento de alertas. Compilação Android somente GitHub Actions, branch isolada; main e original preservados. Testes em aparelho, som com tela bloqueada, Doze e timeout real continuam dependendo de homologação física. Não confundir build bem-sucedido com teste no telefone.

## Referências oficiais
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/about/versions/15/behavior-changes-15
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work