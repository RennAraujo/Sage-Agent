---
id: publico/java/21/virtual-threads.md
fonte: publico
linguagem: java
versao: "21"
secao: Virtual threads
url_origem: https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html
coletado_em: "2026-10-01"
revisado: true
---
# Virtual threads

Virtual threads, a final feature in Java 21, are lightweight threads managed by the JVM instead of the operating system. A program can run millions of them, which makes the thread-per-request style practical again for code that spends most of its time blocked on I/O.

## Creating them

Thread.ofVirtual().start(runnable) starts a virtual thread, and Thread.startVirtualThread(runnable) is the shortcut. To run many tasks, use Executors.newVirtualThreadPerTaskExecutor(), which creates a new virtual thread for every submitted task and should be closed with try-with-resources.

## Guidelines

Do not pool virtual threads. They are cheap, so create one per task and let it end. If you need to limit concurrency, use a Semaphore rather than a fixed-size pool.

Virtual threads do not make CPU-bound code faster, since the number of carrier threads is bounded by the number of processors.

## Pinning

When a virtual thread blocks inside a synchronized block or while a native method is on its stack, it stays pinned to its carrier thread and blocks it. In Java 21 this limits scalability, and the recommendation is to replace long-held synchronized blocks around blocking calls with ReentrantLock. The system property jdk.tracePinnedThreads prints a stack trace when a thread blocks while pinned.

Be careful with ThreadLocal: with many virtual threads, large thread-local caches multiply memory use.
