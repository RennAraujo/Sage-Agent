---
id: interno/portal-demo/runbook-incidentes.md
fonte: interno
linguagem: portal-demo
secao: Runbook de incidentes
coletado_em: "2026-10-01"
revisado: true
---
# Runbook de incidentes do portal-demo

## Severidades

SEV1 é o portal fora do ar ou pagamentos falhando para todos os clientes. SEV2 é uma função importante degradada, como o login lento, ou um problema que afeta uma parte dos clientes. SEV3 é um defeito sem impacto direto no cliente.

## Plantão

O plantão do Squad Portal é semanal e muda toda segunda-feira às 10h. O alerta chega pela ferramenta de plantão e a pessoa de plantão tem 15 minutos para reconhecer um SEV1. Se ninguém reconhecer, o alerta sobe para a coordenação.

## Durante o incidente

Abra uma sala de incidente no chat com o nome incidente-AAAA-MM-DD. Em SEV1, a página de status deve ser atualizada a cada 30 minutos, mesmo que seja para dizer que não há novidade. Para voltar a versão anterior, rode o comando deploy rollback no pipeline de produção, que leva cerca de 6 minutos.

## Depois

Todo SEV1 e SEV2 tem um post-mortem escrito em até 5 dias úteis, sem apontar culpados, com a linha do tempo, a causa e as ações de correção com responsável e prazo.
