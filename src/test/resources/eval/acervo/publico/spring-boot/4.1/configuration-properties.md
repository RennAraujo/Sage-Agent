---
id: publico/spring-boot/4.1/configuration-properties.md
fonte: publico
linguagem: spring-boot
versao: "4.1"
secao: Externalized configuration
url_origem: https://docs.spring.io/spring-boot/4.1/reference/features/external-config.html
coletado_em: "2026-10-01"
revisado: true
---
# Type-safe configuration properties

Annotate a class or a record with @ConfigurationProperties("app.mail") to bind every property that starts with app.mail. Register it with @EnableConfigurationProperties or let @ConfigurationPropertiesScan discover the annotated types in a package.

## Constructor binding

Records and classes with a single parameterized constructor are bound through the constructor and stay immutable. @DefaultValue supplies a value for an absent property. The null-safety annotations from JSpecify are used across the framework, so a parameter without @DefaultValue should be declared as nullable or as an Optional when the property is not required.

## Validation

@Validated on the properties class enables Bean Validation. Violations stop the application at startup. Nested objects are validated only if the field has @Valid.

## Relaxed binding

Kebab case in files, camel case in code and upper case with underscores in environment variables all bind to the same field. The recommended format in properties files is kebab case.

## Durations and sizes

Duration fields accept 30s or 5m and DataSize accepts 10MB. A bare number is milliseconds for a Duration and bytes for a DataSize.
