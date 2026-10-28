---
id: publico/java/21/generational-zgc.md
fonte: publico
linguagem: java
versao: "21"
secao: Generational ZGC
url_origem: https://docs.oracle.com/en/java/javase/21/gctuning/z-garbage-collector.html
coletado_em: "2026-10-01"
revisado: true
---
# Generational ZGC

Java 21 extends the Z Garbage Collector with generations. Young and old objects are collected separately, which takes advantage of the fact that most objects die young and lowers both CPU overhead and the risk of allocation stalls.

## Enabling it

Generational mode is opt-in in Java 21. Start the JVM with -XX:+UseZGC -XX:+ZGenerational. Without the second flag, -XX:+UseZGC still selects the single-generation collector.

ZGC keeps pauses short, normally under one millisecond, independent of heap size, because marking and relocation run concurrently with the application threads. The trade-off is some extra memory for the colored pointers and load barriers.
