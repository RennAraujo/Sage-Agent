---
id: publico/spring-boot/3.5/virtual-threads.md
fonte: publico
linguagem: spring-boot
versao: "3.5"
secao: Virtual threads
url_origem: https://docs.spring.io/spring-boot/3.5/reference/features/task-execution-and-scheduling.html
coletado_em: "2026-10-01"
revisado: true
---
# Virtual threads

On Java 21 or later, set spring.threads.virtual.enabled=true to run Spring Boot on virtual threads. The default is false.

## What changes

With the property enabled, the embedded Tomcat or Jetty handles each request on a virtual thread. The auto-configured applicationTaskExecutor, used by @Async and by Spring MVC for async requests, becomes a virtual thread executor, and the scheduler used by @Scheduled runs its tasks on virtual threads as well.

## Caveats

Virtual threads do not speed up CPU-bound work. Connection pools still bound the concurrency of the database, so a pool of 10 connections stays a limit of 10 concurrent queries. Blocking while holding a monitor can pin the carrier thread on Java 21, so prefer ReentrantLock for long critical sections.

On a Java version older than 21 the property is ignored and a platform thread pool is used.
