---
id: publico/java/21/pattern-switch.md
fonte: publico
linguagem: java
versao: "21"
secao: Pattern matching for switch
url_origem: https://docs.oracle.com/en/java/javase/21/language/pattern-matching-switch.html
coletado_em: "2026-10-01"
revisado: true
---
# Pattern matching for switch

Pattern matching for switch is a final feature in Java 21. A case label can be a type pattern such as case String s, which tests the type of the selector and binds a variable in one step. The selector can have any reference type, not just enums, strings and integers.

## Guards

A guard refines a pattern with the when keyword: case Integer i when i > 100 -> "big". The case matches only if the pattern matches and the boolean expression is true. Older previews used the && operator for this; Java 21 uses when.

## Null and dominance

A switch with patterns can have case null, which handles a null selector instead of throwing NullPointerException. Without case null, a null selector still throws.

The compiler rejects a case label that is dominated by an earlier one. A guarded case for a type has to come before the unguarded case of the same type, otherwise the first would never be reached.

## Exhaustiveness

A switch expression must be exhaustive. For sealed types the compiler accepts a switch without default when all permitted subclasses are covered. Record patterns can appear as case labels and be nested: case Line(Point(var x1, var y1), Point p2).
