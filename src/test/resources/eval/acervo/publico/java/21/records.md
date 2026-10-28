---
id: publico/java/21/records.md
fonte: publico
linguagem: java
versao: "21"
secao: Records
url_origem: https://docs.oracle.com/en/java/javase/21/language/records.html
coletado_em: "2026-10-01"
revisado: true
---
# Records

A record is a restricted kind of class meant to carry immutable data. You declare the components in the header, for example record Point(int x, int y), and the compiler generates a private final field and an accessor for each component, plus a canonical constructor, equals, hashCode and toString.

## Constructors

A compact constructor omits the parameter list and the field assignments. It is the place to validate or normalize arguments before the fields are assigned, for example by throwing IllegalArgumentException when a value is negative. The assignment of the fields happens automatically at the end of the compact constructor.

You can add other constructors, but each one must delegate to the canonical constructor with this(...).

## Restrictions

A record cannot extend another class, because it already extends java.lang.Record, and it is implicitly final. It cannot declare instance fields beyond its components. A record can implement interfaces, declare static fields and static factory methods, and be nested or declared locally inside a method.

## Record patterns

Record patterns, final in Java 21, deconstruct a record in instanceof and switch: if (o instanceof Point(int x, int y)) binds x and y directly. Patterns can be nested to take apart records that contain other records.
