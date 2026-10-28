---
id: publico/java/21/streams.md
fonte: publico
linguagem: java
versao: "21"
secao: Streams
url_origem: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/package-summary.html
coletado_em: "2026-10-01"
revisado: true
---
# Streams

A stream is a sequence of elements that supports aggregate operations. It does not store data: it pulls elements from a source such as a collection, an array or a generator function, and runs a pipeline of intermediate operations followed by one terminal operation.

## Collecting results

Stream.toList() is a terminal operation that returns an unmodifiable List with the elements of the stream, in encounter order. Calling add or remove on the returned list throws UnsupportedOperationException. The list may contain null elements.

Collectors.toList() is different: it returns an ArrayList, but the specification gives no guarantee about the type, mutability or thread safety of the list. Use Collectors.toCollection(ArrayList::new) when you need a mutable list for sure.

Collectors.teeing combines two downstream collectors and merges their results with a function, which lets one pass over the stream compute, for example, both a count and a sum.

## Slicing and expanding

takeWhile keeps elements while a predicate is true and stops at the first failure. dropWhile does the opposite and discards the leading elements that match.

Stream.mapMulti replaces each element with zero or more elements pushed into a consumer. It is useful when producing a small number of results per element, because it avoids creating a new stream for each one, which flatMap would do.
