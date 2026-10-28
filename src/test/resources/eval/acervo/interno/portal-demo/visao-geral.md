---
id: interno/portal-demo/visao-geral.md
fonte: interno
linguagem: portal-demo
secao: Visão geral
coletado_em: "2026-10-01"
revisado: true
---
# Visão geral do portal-demo

O portal-demo é o portal de autoatendimento dos clientes da loja. Por ele o cliente consulta pedidos, troca o endereço de entrega, paga faturas e abre chamados de suporte. O time dono é o Squad Portal, e o canal de dúvidas é o #squad-portal no chat corporativo.

## Arquitetura

O portal-demo é um monólito modular em Spring Boot que roda em três instâncias atrás de um balanceador. O banco é um PostgreSQL gerenciado, com uma réplica de leitura usada pelos relatórios. O cache de sessão fica em um Redis compartilhado com a aplicação de catálogo.

As rotinas em lote (conciliação de pagamentos e envio de notas fiscais) rodam em um serviço separado, o portal-demo-batch, que usa o mesmo banco.

## Ambientes

Existem três ambientes: dev, homologação e produção. A homologação recebe o deploy automaticamente a cada merge na branch principal. O deploy em produção é manual, aprovado por duas pessoas do Squad Portal, e acontece de terça a quinta, nunca às sextas-feiras.

## Para onde ir

O fluxo de compra está em [[fluxo-checkout]]. Login e sessão estão em [[autenticacao]]. Em caso de incidente, siga o [[runbook-incidentes]].
