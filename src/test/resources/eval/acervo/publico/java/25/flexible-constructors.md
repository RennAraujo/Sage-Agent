---
id: publico/java/25/flexible-constructors.md
fonte: publico
linguagem: java
versao: "25"
secao: Flexible constructor bodies
url_origem: https://docs.oracle.com/en/java/javase/25/language/flexible-constructor-bodies.html
coletado_em: "2026-10-01"
revisado: true
---
# Flexible constructor bodies

Flexible constructor bodies are a final feature in Java 25. A constructor can now run statements before it calls super(...) or this(...). That region of the body is called the prologue.

## What the prologue can do

The prologue can validate arguments, compute values to pass to the superclass constructor and initialize fields of the class being constructed. Before this change the only way to validate an argument that was going to the superclass was a static helper method called inside the super(...) expression.

## What it cannot do

Code in the prologue cannot read the instance under construction. It cannot call instance methods, read inherited fields or use this, because the superclass part of the object has not been initialized yet. Assigning the fields declared by the class itself is allowed, so an overridden method called by the superclass constructor sees the right value instead of a default.
