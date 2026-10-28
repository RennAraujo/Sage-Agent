---
id: publico/java/25/sealed-classes.md
fonte: publico
linguagem: java
versao: "25"
secao: Sealed classes
url_origem: https://docs.oracle.com/en/java/javase/25/language/sealed-classes-and-interfaces.html
coletado_em: "2026-10-01"
revisado: true
---
# Sealed classes and interfaces

A sealed class or interface lists the classes allowed to extend or implement it after the permits keyword. Each permitted subclass must be final, sealed or non-sealed. When the subclasses live in the same source file the permits clause can be left out.

## Exhaustive switch

A switch over a sealed type that covers every permitted subclass is exhaustive without a default branch. If a new subclass is added to the hierarchy later, every switch that does not handle it stops compiling, which makes sealed types a good fit for modeling a closed set of results or commands.

Permitted subclasses must belong to the same module, or to the same package when the sealed type is in the unnamed module.
