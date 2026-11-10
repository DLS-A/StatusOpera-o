# Implantação atual — Status Operacional 0.4.0-test

Esta versão usa **ativação única por telefone**, não login OAuth. Este documento prevalece sobre as instruções históricas de instalação/autenticação.

## Uso

1. Desinstale somente o APK de teste anterior e instale o novo APK entregue. As assinaturas debug são diferentes; ajustes locais antigos serão perdidos. Não remova outro aplicativo produtivo.
2. Abra o aplicativo com internet e digite o código fornecido privadamente pelo responsável.
3. Depois da ativação, o app abre direto. Cada telefone recebe sua própria credencial cifrada no Android Keystore; o código compartilhado não fica salvo.
4. Reinstalação ou limpeza dos dados exige o código novamente. Não use “Desativar telefone” apenas para fechar o aplicativo: essa ação revoga a credencial e apaga o cache local.

O código não pertence ao APK ou ao repositório. Qualquer pessoa que o conheça pode ativar outros telefones. Compartilhe somente com a equipe autorizada. A troca do hash no servidor bloqueia novas ativações com o código antigo, mas não revoga automaticamente aparelhos já ativados.

## Infraestrutura verificada

- Backend: https://status-operacional-api.onrender.com — Render `free`, Docker, região Virginia.
- Banco: Supabase `pfhlljjqltwqfbfbalpw`, schema `operational` privado, RLS habilitada, sem acesso `anon`/`authenticated`.
- Quatro migrations aplicadas, incluindo `20261110010000_device_activation`.
- Usuário runtime restrito, não superuser e sem acesso ao schema Auth. Conexão pooler oficial com TLS `verify-full` e CA confiável.
- Tokens GRM, senha PostgreSQL e hash do código são configuração exclusiva do servidor. Somente a URL pública fica no APK.
- `AUTH_MODE=activation`, FCM desativado, OAuth Supabase desativado. Endpoints de login OAuth do backend indisponíveis nesta modalidade.

## Validação executada

Em 10/11/2026, a consulta real GRM foi normalizada com 1493 registros e 93 equipamentos; as contagens variam com a operação. Snapshot remoto com dados frescos, origem disponível e persistência confirmada. Sem bearer: 401. Código errado: 403. Sessão e readiness autenticados: 200. Revogação individual: telefone revogado 401 enquanto outro permanece 200. Credenciais de teste foram removidas após a verificação.

67 testes Python aprovados, incluindo integração PostgreSQL local descartável e limitação persistente das tentativas. GitHub Actions `42823754826` concluído com sucesso, 9 JUnit aprovados, assinatura v2 do APK verificada. SHA-256: `024dbee93ef4a89bfa218be365ad2387abaebef1f7746da1e7a096249d304cb3`. Não foi executado teste físico/emulador neste sandbox.

## Limites

- O plano gratuito do Render pode suspender após inatividade. A primeira consulta pode demorar; aguarde ou use “Tentar conexão”. A ativação salva é preservada em falhas temporárias de rede.
- Monitoramento automático somente com o aplicativo em primeiro plano. **Sem push ou garantia de alertas com o app fechado.**
- Cache cifrado pode ser consultado por até 24 horas desde a última verificação, se houver dados válidos salvos; será marcado como desatualizado. Sem conexão, a revogação remota só será detectada na próxima verificação ou ao encerrar essa janela.
- O GRM é consultado de forma centralizada, preservando a janela do início do dia anterior em São Paulo até agora. Apontamentos antigos fora dessa janela podem não aparecer.
- Classificação “em operação/parado” depende de categorias KPI administrativamente confirmadas; ausência de apontamento não significa equipamento parado.
- APK debug para teste/distribuição interna, não release homologado. Builds futuros precisam de chave de assinatura permanente para atualizações confiáveis.

## Manutenção administrativa

Definir `ACTIVATION_CODE_SHA256` como SHA-256 do código com espaços externos removidos e letras maiúsculas. O código original é entregue somente ao responsável. Limites atuais: 10 tentativas por IP e 200 globais em 15 minutos; contadores persistem após reinício.

Para revogar um telefone específico, definir `enabled=false` somente no UUID correspondente de `operational.activated_devices`. Nunca usar a identidade de um telefone como autorização para outro. O servidor persiste apenas o hash SHA-256 da credencial individual.

Não reaplicar migrations cegamente. Não habilitar FCM apenas mudando uma flag: as rotas de ativação rejeitam registro push nesta versão, e a modalidade precisaria de implementação/testes próprios.