---
id: publico/java/25/virtual-threads.md
fonte: publico
linguagem: java
versao: "25"
secao: Virtual threads
url_origem: https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html
coletado_em: "2026-10-01"
revisado: true
---
# Virtual threads

Virtual threads are lightweight threads scheduled by the JVM. Create one with Thread.ofVirtual().start(runnable) or run many tasks with Executors.newVirtualThreadPerTaskExecutor(). They should not be pooled: create one per task.

## Pinning

A virtual thread that blocks while inside a synchronized block or method no longer pins its carrier thread. Since Java 24 the monitor is released while the virtual thread waits, so the carrier can run other virtual threads. Pinning still happens when a native method or a foreign function is on the stack, and the old advice of replacing synchronized with ReentrantLock is no longer needed for scalability.

## Scoped values

Scoped values, final in Java 25, share immutable data with callees and child threads for a bounded period of execution. They are a lighter alternative to ThreadLocal, because the value is bound for the duration of ScopedValue.where(KEY, value).run(...) and then disappears, with no remove call to forget.

## Structured concurrency

The structured concurrency API (StructuredTaskScope) is still a preview feature in Java 25. It treats a group of related subtasks as one unit of work, so that a failure in one subtask cancels the others and no thread outlives its scope. It needs --enable-preview.
