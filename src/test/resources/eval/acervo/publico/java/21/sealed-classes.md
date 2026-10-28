---
id: publico/java/21/sealed-classes.md
fonte: publico
linguagem: java
versao: "21"
secao: Sealed classes
url_origem: https://docs.oracle.com/en/java/javase/21/language/sealed-classes-and-interfaces.html
coletado_em: "2026-10-01"
revisado: true
---
# Sealed classes and interfaces

A sealed class or interface restricts which other classes may extend or implement it. The permitted subclasses are listed after the permits keyword: sealed interface Shape permits Circle, Square.

## Rules for subclasses

Every permitted subclass must be declared final, sealed or non-sealed. Final ends the hierarchy, sealed continues it with its own permits clause, and non-sealed opens that branch again to any subclass. If the permitted subclasses are in the same source file, the permits clause may be omitted and the compiler infers them.

Permitted subclasses must be in the same module as the sealed class or, in the unnamed module, in the same package.

## Why use them

Sealed hierarchies model a closed set of alternatives. Combined with switch pattern matching, the compiler can check that a switch over a sealed type covers every permitted subclass, so no default branch is needed and adding a new subclass turns every incomplete switch into a compile error.
