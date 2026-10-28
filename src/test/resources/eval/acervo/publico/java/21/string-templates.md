---
id: publico/java/21/string-templates.md
fonte: publico
linguagem: java
versao: "21"
secao: String templates (preview)
url_origem: https://docs.oracle.com/en/java/javase/21/language/string-templates.html
coletado_em: "2026-10-01"
revisado: true
---
# String templates (preview)

String templates are a preview language feature in Java 21. They let you embed expressions in a string literal with the \{expression} syntax and have a template processor turn the result into a string or another object.

## Using them

The STR processor is imported automatically: STR."Hello \{name}, you are \{age} years old" evaluates the embedded expressions and concatenates the result. The FMT processor accepts printf-style format specifiers before each expression. RAW gives access to the template before processing.

## Enabling the preview

Preview features are off by default. Compile and run with --enable-preview and --release 21. Code compiled with preview features only runs on the same Java version, and the API may change or be removed in later releases.
