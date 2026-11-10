# Uso do aplicativo

## Entrar

Instalar o APK **novo, assinado e validado externamente** pelo responsável. Nenhum APK foi gerado nesta entrega. Se “Entrar com segurança” estiver desabilitado, falta configuração administrativa do build; não preencher API/token no telefone.

Entrar na página OAuth usando conta confirmada/autorizada. O app valida a identidade no backend antes de apresentar dados. 401/403 encerra a sessão local e limpa cache; falta de rede não autoriza outro usuário a ler dados anteriores.

## Cinco abas preservadas

1. **Início:** resumo, conexão, contagens e atualização manual. Operando/parado dependem de classificação KPI confirmada; desconhecido não deve ser reinterpretado como parado.
2. **Equipamentos:** pesquisa, filtros por status e detalhes do apontamento atual. Sem apontamento não significa estado operacional confirmado.
3. **Pausas:** pausas abertas e duração. Limite padrão de pausa ergonômica: 15 minutos, estritamente ultrapassado para alertar.
4. **Manutenções:** dados segundo as regras originais. Avisos de novas manutenções usam primeiro snapshot como baseline, evitando flood de eventos antigos ao entrar.
5. **Configurações:** intervalo do telefone, limite/alerta por status, repetição local, som e controle visual, permissão e restauração de padrões. Não contém credenciais de servidor.

## Atualização e dados antigos

- Automático com app em primeiro plano: 60 segundos por padrão, configurável entre 30 e 3600.
- Atualização manual disponível; requisições simultâneas são evitadas.
- Cada telefone lê o mesmo snapshot de servidor. Alterar intervalo local não aumenta a frequência do GET GRM no servidor.
- Banner de desatualização aparece quando fonte/persistência falha ou o snapshot envelhece. Durações ficam congeladas no último valor confirmado; não surgem novos alertas por extrapolação de dado stale.
- Datas estão no fuso `America/Sao_Paulo`. Conferir unidade/horário com dados GRM reais na homologação.
- Cache cifrado pode ser restaurado somente para usuário/sessão anteriormente validados e com access token ainda vigente. Não há acesso offline ilimitado nem confirmação remota de revogação quando sem rede.

## Alertas e privacidade

A notificação diz apenas que existe uma atualização de monitoramento e solicita abrir o aplicativo. Não expõe equipamento, operador ou status em payload/tela bloqueada. O volume e canais continuam sob controle do Android; “som habilitado” no app não ignora modo silencioso, permissões ou configuração do sistema.

Reconhecer um alerta silencia seu hash local e cancela a notificação. Deduplicação local é compartilhada com FCM, por sessão, com retenção limitada. Não sincroniza reconhecimento para outros telefones nem modifica o apontamento GRM.

Repetição configurável é **local, em primeiro plano**. FCM opcional permite avisos genéricos sem o polling aberto, mas exige projeto/credencial/permissão/servidor reais e homologação. Não esperar push após force-stop. Notificações já em trânsito e indisponibilidades podem gerar atraso ou falta de entrega; não usar como único canal crítico.

## Sair

Logout remove imediatamente os dados operacionais da tela e o cache/identidade local, cancela requisição e notificações e tenta encerrar sessão no servidor/Supabase. A mensagem informa se encerramento remoto foi confirmado. Se não confirmado, avisar o administrador para revogar remotamente; limpar aparelho não equivale a revogar sessão no provedor.