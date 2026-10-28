---
id: publico/java/25/sequenced-collections.md
fonte: publico
linguagem: java
versao: "25"
secao: Sequenced collections
url_origem: https://docs.oracle.com/en/java/javase/25/core/creating-sequenced-collections-sets-and-maps.html
coletado_em: "2026-10-01"
revisado: true
---
# Sequenced collections

SequencedCollection, SequencedSet and SequencedMap describe collections with a defined encounter order. A SequencedCollection offers getFirst, getLast, addFirst, addLast, removeFirst, removeLast and reversed, and reversed returns a live view in the opposite order.

List, Deque, LinkedHashSet, SortedSet, LinkedHashMap and SortedMap are sequenced. HashSet and HashMap are not. Calling getFirst on an empty collection throws NoSuchElementException.
