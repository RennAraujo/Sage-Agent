---
id: publico/java/25/compact-source-files.md
fonte: publico
linguagem: java
versao: "25"
secao: Compact source files and instance main methods
url_origem: https://docs.oracle.com/en/java/javase/25/language/compact-source-files.html
coletado_em: "2026-10-01"
revisado: true
---
# Compact source files and instance main methods

Java 25 finalizes a simpler way to write small programs. A source file can declare methods and fields at the top level, and the compiler wraps them in an implicitly declared class. The launcher accepts a main method that is not static, has no String[] parameter or is not public, so a complete program can be written as void main() { ... }.

## Console helpers

The java.lang.IO class offers println, print and readln for console input and output without importing System. Its methods are static and can be used from any class, but they are meant for learning and quick scripts.

## Growing up

An implicitly declared class is always final, belongs to the unnamed package and cannot be referenced from other classes. When the program grows, wrap the code in a regular class and keep the same methods.
