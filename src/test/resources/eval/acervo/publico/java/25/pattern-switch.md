---
id: publico/java/25/pattern-switch.md
fonte: publico
linguagem: java
versao: "25"
secao: Pattern matching for switch
url_origem: https://docs.oracle.com/en/java/javase/25/language/pattern-matching-switch.html
coletado_em: "2026-10-01"
revisado: true
---
# Pattern matching for switch

A case label in a switch can be a type pattern, a record pattern or a constant, and the selector can have any reference type. A guard written with the when keyword narrows a case: case Integer i when i > 100 -> "big". A switch can include case null to handle a null selector.

## Dominance and exhaustiveness

A case that is dominated by an earlier case is a compile-time error, so the more specific or guarded cases go first. A switch expression must be exhaustive, and a switch over a sealed type is exhaustive when every permitted subclass is covered.

## Primitive patterns (preview)

Java 25 previews primitive types in patterns, instanceof and switch (a preview feature, enabled with --enable-preview). With it a switch can use a selector of type long, float or double and a pattern such as case int i matches only when the value converts to int without loss of information.
