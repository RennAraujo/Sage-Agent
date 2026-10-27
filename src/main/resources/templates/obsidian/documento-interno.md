---
id: interno/portal-x/faturamento.md
fonte: interno
linguagem: java
secao: Faturamento
aliases: [Cobrança, "módulo de faturamento"]
coletado_em: 2026-10-01
revisado: false
---

# Faturamento do Portal X

Descreva como o sistema funciona hoje: regras de negócio, fluxos, integrações e decisões que não estão óbvias no
código. Não cole senhas, tokens nem chaves: o envio é recusado se o texto parecer conter credenciais.

## Fluxo principal

1. O pedido é fechado no portal.
2. O serviço de faturamento gera a cobrança e publica o evento de nota fiscal.
