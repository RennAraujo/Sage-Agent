---
id: publico/java/21/sequenced-collections.md
fonte: publico
linguagem: java
versao: "21"
secao: Sequenced collections
url_origem: https://docs.oracle.com/en/java/javase/21/core/creating-sequenced-collections-sets-and-maps.html
coletado_em: "2026-10-01"
revisado: true
---
# Sequenced collections

Java 21 adds three interfaces for collections with a defined encounter order: SequencedCollection, SequencedSet and SequencedMap. Before them there was no common way to ask a List, a Deque or a LinkedHashSet for its first or last element.

## Methods

SequencedCollection declares getFirst, getLast, addFirst, addLast, removeFirst, removeLast and reversed. On an empty collection getFirst and getLast throw NoSuchElementException.

reversed returns a view in the opposite order, not a copy. Changes made through the view are visible in the original, and the other way around.

## Who implements them

List and Deque extend SequencedCollection, LinkedHashSet and SortedSet implement SequencedSet, and LinkedHashMap and SortedMap implement SequencedMap, which adds firstEntry, lastEntry, pollFirstEntry, pollLastEntry, putFirst and putLast. HashSet and HashMap are not sequenced, because their iteration order is unspecified.
