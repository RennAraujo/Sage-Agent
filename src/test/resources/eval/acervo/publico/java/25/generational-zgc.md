---
id: publico/java/25/generational-zgc.md
fonte: publico
linguagem: java
versao: "25"
secao: Z Garbage Collector
url_origem: https://docs.oracle.com/en/java/javase/25/gctuning/z-garbage-collector.html
coletado_em: "2026-10-01"
revisado: true
---
# Z Garbage Collector

In Java 25 the Z Garbage Collector is always generational. The single-generation mode was removed, so -XX:+UseZGC selects the generational collector and the -XX:+ZGenerational flag is obsolete and has no effect.

ZGC keeps pause times short, normally under one millisecond, regardless of the heap size, because it marks and relocates objects concurrently with the application. Generational collection reduces the CPU cost and the allocation stalls compared with the single-generation mode.
