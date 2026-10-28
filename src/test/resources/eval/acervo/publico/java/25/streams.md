---
id: publico/java/25/streams.md
fonte: publico
linguagem: java
versao: "25"
secao: Streams
url_origem: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/package-summary.html
coletado_em: "2026-10-01"
revisado: true
---
# Streams

A stream is a sequence of elements that supports aggregate operations. It pulls elements from a source and runs a pipeline of intermediate operations followed by one terminal operation.

## Collecting results

Stream.toList() returns an unmodifiable List with the elements of the stream, in encounter order. Modifying the returned list throws UnsupportedOperationException. Collectors.toList() gives no guarantee about the type or mutability of its result, so use Collectors.toCollection when a mutable list is required.

Collectors.teeing runs two collectors over the same pass and merges their results.

## Gatherers

Stream gatherers, final in Java 25, let a pipeline define its own intermediate operations. Stream.gather(Gatherer) applies a gatherer, and the java.util.stream.Gatherers class has ready-made ones: windowFixed groups elements into lists of a fixed size, windowSliding builds overlapping windows, fold and scan accumulate state, and mapConcurrent maps elements concurrently with a bound on the number of tasks, using virtual threads.

A gatherer can keep private state, emit zero or more elements for each input and short-circuit the stream, which is what makes operations such as windowing possible without collecting the whole stream first.
