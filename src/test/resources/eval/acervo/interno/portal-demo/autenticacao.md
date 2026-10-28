---
id: interno/portal-demo/autenticacao.md
fonte: interno
linguagem: portal-demo
secao: Autenticação e sessão
coletado_em: "2026-10-01"
revisado: true
---
# Autenticação e sessão no portal-demo

O cliente entra com e-mail e senha. A senha precisa ter pelo menos 10 caracteres e é guardada apenas como hash com bcrypt.

## Bloqueio

Depois de 5 tentativas de login erradas seguidas, a conta fica bloqueada por 15 minutos. O contador zera quando o login dá certo. O suporte pode desbloquear a conta antes do prazo, mas só depois de confirmar a identidade do cliente.

## Segundo fator

Contas de administrador do portal são obrigadas a usar um segundo fator por aplicativo autenticador (TOTP). Para clientes comuns o segundo fator é opcional e, se ativado, usa o código enviado por SMS.

## Sessão

A sessão expira depois de 30 minutos sem atividade. O token de renovação dura 7 dias e é revogado quando o cliente troca a senha. A sessão fica no Redis compartilhado, e por isso o logout em uma instância vale para todas.

## Recuperação de senha

O link de redefinição de senha é enviado por e-mail e expira em 1 hora. Cada link só pode ser usado uma vez.
