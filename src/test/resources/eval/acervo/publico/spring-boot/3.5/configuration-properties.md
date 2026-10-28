---
id: publico/spring-boot/3.5/configuration-properties.md
fonte: publico
linguagem: spring-boot
versao: "3.5"
secao: Externalized configuration
url_origem: https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html
coletado_em: "2026-10-01"
revisado: true
---
# Type-safe configuration properties

Annotate a class or a record with @ConfigurationProperties("app.mail") to bind every property that starts with app.mail to its fields. Register it with @EnableConfigurationProperties(MailProperties.class) on a configuration class, or let @ConfigurationPropertiesScan find all of them in a package.

## Constructor binding

A record, or a class with a single parameterized constructor, is bound through the constructor and stays immutable. Use @DefaultValue on a parameter to supply a value when the property is absent. With a class that has several constructors, mark the one to use with @ConstructorBinding.

## Validation

Add @Validated to the properties class and Bean Validation annotations such as @NotBlank or @Min to the fields. The application fails to start with a clear message when a constraint is violated, which is better than discovering a bad value in production. Nested objects are validated only when the field is annotated with @Valid.

## Relaxed binding

Property names are matched loosely. The property app.mail.from-address, the environment variable APP_MAIL_FROMADDRESS and the key app.mail.fromAddress all bind to the Java field fromAddress. Kebab case is the recommended format in properties files.

## Durations and sizes

Fields of type Duration accept values like 30s or 5m, and DataSize accepts 10MB. Without a unit, the number is read in milliseconds for a Duration and in bytes for a DataSize.
