---
id: publico/spring-boot/4.1/http-clients.md
fonte: publico
linguagem: spring-boot
versao: "4.1"
secao: HTTP clients
url_origem: https://docs.spring.io/spring-boot/4.1/reference/io/rest-client.html
coletado_em: "2026-10-01"
revisado: true
---
# Calling REST services

RestClient is the recommended synchronous client. Inject the auto-configured RestClient.Builder, set the base URL once and build a client per target service.

## Timeouts

The properties spring.http.client.connect-timeout and spring.http.client.read-timeout set the timeouts of every client built from the auto-configured builder. Without them a slow server can hold a thread indefinitely, so set both in production.

## Declarative clients

Describe a remote API as a Java interface with methods annotated with @GetExchange or @PostExchange, and register it with @ImportHttpServices(groups = ...). Spring generates the implementation on top of RestClient, and the group name lets you configure the base URL and timeouts per service in the properties.

## API versioning

Spring Boot 4 can route requests by API version. Properties such as spring.mvc.apiversion.use.header choose where the version comes from, and a controller method declares the versions it handles in its mapping annotation.
