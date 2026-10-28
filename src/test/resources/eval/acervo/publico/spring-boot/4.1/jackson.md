---
id: publico/spring-boot/4.1/jackson.md
fonte: publico
linguagem: spring-boot
versao: "4.1"
secao: JSON serialization with Jackson 3
url_origem: https://docs.spring.io/spring-boot/4.1/reference/features/json.html
coletado_em: "2026-10-01"
revisado: true
---
# JSON serialization with Jackson 3

Spring Boot 4 uses Jackson 3 as the default JSON library. The packages moved from com.fasterxml.jackson to tools.jackson, with the exception of the annotations, which keep the old package name. The main entry point is JsonMapper, and an auto-configured JsonMapper bean is available for injection.

## Configuration

Properties under spring.jackson.* still customize the mapper, for example spring.jackson.default-property-inclusion=non-null. Beans of type JsonMapperBuilderCustomizer can adjust the builder in code. Jackson 3 mappers are immutable once built, so configuration happens on the builder.

## Jackson 2

Existing code that still depends on Jackson 2 can keep it on the classpath, and a deprecated compatibility module bridges the two versions for a while. New code should use the Jackson 3 packages.

## Unchecked exceptions

Jackson 3 exceptions extend RuntimeException, so methods that read or write JSON no longer need to declare or catch a checked JsonProcessingException.
