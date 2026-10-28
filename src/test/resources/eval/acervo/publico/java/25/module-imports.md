---
id: publico/java/25/module-imports.md
fonte: publico
linguagem: java
versao: "25"
secao: Module import declarations
url_origem: https://docs.oracle.com/en/java/javase/25/language/module-import-declarations.html
coletado_em: "2026-10-01"
revisado: true
---
# Module import declarations

A module import declaration, final in Java 25, imports on demand every public top-level class and interface exported by a module: import module java.base; makes List, Map, Stream, Path and the rest of the packages exported by java.base available without listing each package.

## Ambiguities

If two imported modules export classes with the same simple name, using that name is a compile-time error until you add a single-type import that picks one of them. A single-type import or a class declared in the file always wins over a module import.

## When to use it

It is aimed at scripts, small programs and teaching, where long lists of imports get in the way. Library code usually keeps explicit imports because they document the dependencies of the file.
