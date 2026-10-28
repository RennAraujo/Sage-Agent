---
id: publico/spring-boot/4.1/virtual-threads.md
fonte: publico
linguagem: spring-boot
versao: "4.1"
secao: Virtual threads
url_origem: https://docs.spring.io/spring-boot/4.1/reference/features/task-execution-and-scheduling.html
coletado_em: "2026-10-01"
revisado: true
---
# Virtual threads

Set spring.threads.virtual.enabled=true to run the application on virtual threads. The property defaults to false and needs Java 21 or later, and Spring Boot 4.1 supports running on Java 25.

## What changes

The embedded web server handles requests on virtual threads, the applicationTaskExecutor used by @Async becomes a virtual thread executor and scheduled tasks run on virtual threads. Database connection pools still limit concurrency: ten connections allow ten concurrent queries, whatever the number of threads.

## Pinning

On Java 24 and later a virtual thread no longer pins its carrier while it waits inside a synchronized block, so the advice to replace synchronized with ReentrantLock only applies to Java 21. Native calls still pin.
