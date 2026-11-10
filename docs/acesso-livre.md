# Status Operacional 0.5.0-test — acesso livre e apontamentos atuais

Esta versão substitui a ativação por código: **instalar e abrir**, sem e-mail, login ou código por telefone. As instruções antigas de OAuth/ativação não se aplicam.

## Correções

- Equipamentos mostra o apontamento atual recebido do GRM, com motivo, duração, operador e local.
- Manutenções inclui os quatro tipos: `AG MANUTENCAO`, `EXEC MANUT CORRETIVA`, `EXEC MANUT PREVENTIVA` e `EXEC MANUT PREVENTIVA-250 HRS`. Aceita também `EXEC MANUT PREVENTIVA - 250 HRS` e variações de espaços/acentos.
- Pausas mostra `PAUSA ERGONOMICA`/`PAUSA ERGONÔMICA` quando entrarem no conjunto atual. Pausas alimentam a avaliação dos alertas, inclusive se não estiverem duplicadas na lista de equipamentos.
- Duração usa o intervalo informado no relatório e avança durante a coleta válida, evitando somar diferenças de fuso ao tempo do apontamento.
- Consulta pública somente leitura. Token GRM e senha PostgreSQL continuam exclusivos do servidor; banco permanece privado. Escritas operacionais e registro push não são liberados.

## Contrato real observado

O relatório `status-log-base-report` devolve `endDate` preenchido inclusive para os apontamentos atuais, limitados ao mesmo horário final móvel da coleta. A hipótese anterior de que apenas `endDate=null/0` significaria atual fazia as três listas ficarem vazias.

Em consultas consecutivas, CB-303 e CB-310 mantiveram o mesmo ID/início enquanto `endDate` e `logDuration` avançaram. A coleta apresentava 93 máquinas com o mesmo corte final. O adaptador agora usa esse conjunto de corte comum para esse formato, mantendo suporte a `endDate=null/0`. Não toma simplesmente o último registro histórico de cada máquina como ativo; registros encerrados fora do corte são excluídos. Um único registro histórico isolado não é reclassificado como atual.

## Validação

Após publicação, consulta sem bearer retornou 200 com 93 apontamentos atuais e 16 manutenções. CB-303 corretiva e CB-310 preventiva presentes tanto em Equipamentos como Manutenções. Readiness público 200; ativação antiga 404; escrita no snapshot 405; registro de dispositivo sem autenticação 401. Contagens variam com a operação.

Não havia pausa atual na validação real. Testes de regressão simulam pausa chegando, ultrapassando o limite e encerrando; ausência de pausa no momento não é apresentada como falha nem gera alerta falso.

72 testes Python/PostgreSQL local aprovados. Testes Android incluem duração sem inflação por fuso, alerta pelo conjunto de Pausas e congelamento de dados desatualizados. Conferir execução/assinatura no arquivo de evidências da build entregue.

## Instalar e usar

Instale o APK entregue e abra com internet. Se o Android indicar conflito de assinatura, remova somente a versão anterior de teste, sabendo que os ajustes locais serão perdidos. Configure notificações e o limite desejado em Ajustes; pausa ergonômica tem limite padrão de 15 minutos, com alerta somente após ultrapassá-lo.

O app e o endereço do backend são abertos: qualquer pessoa com o APK ou URL pode consultar os dados da obra, conforme solicitado. Não compartilhe com pessoas fora do público desejado se os dados forem sensíveis.

Atualização e novos alertas locais durante uso em primeiro plano. Sem push com app fechado. Render gratuito pode demorar na primeira resposta após inatividade. Em falha de conexão, o último cache cifrado aparece marcado como desatualizado, sem gerar novos alertas a partir dele. APK debug interno; não houve teste físico/emulador neste sandbox.