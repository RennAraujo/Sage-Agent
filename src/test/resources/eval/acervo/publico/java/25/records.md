---
id: publico/java/25/records.md
fonte: publico
linguagem: java
versao: "25"
secao: Records
url_origem: https://docs.oracle.com/en/java/javase/25/language/records.html
coletado_em: "2026-10-01"
revisado: true
---
# Records

A record is a restricted kind of class that carries immutable data. The components in the header, as in record Point(int x, int y), produce private final fields, accessors, a canonical constructor, equals, hashCode and toString.

## Constructors

A compact constructor has no parameter list and runs before the fields are assigned, so it is the right place to validate arguments. Other constructors must call the canonical one with this(...).

## Restrictions

A record is implicitly final and cannot extend a class. It may implement interfaces, declare static members and be declared locally. Instance fields other than the components are not allowed.

## Unnamed variables

Since Java 22 an underscore can replace a name that is not used. In a record pattern you write if (o instanceof Point(int x, _)) when the second component does not matter, and a catch parameter or lambda parameter that is ignored can also be written as an underscore.
