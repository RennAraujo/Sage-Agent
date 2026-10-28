---
id: publico/spring-boot/3.5/profiles.md
fonte: publico
linguagem: spring-boot
versao: "3.5"
secao: Profiles
url_origem: https://docs.spring.io/spring-boot/3.5/reference/features/profiles.html
coletado_em: "2026-10-01"
revisado: true
---
# Profiles

Profiles let you load different beans and properties per environment. Activate them with spring.profiles.active=prod, with the environment variable SPRING_PROFILES_ACTIVE or with the command line argument --spring.profiles.active=prod. Several profiles are separated by commas.

## Profile-specific files

For each active profile Spring Boot also reads application-{profile}.properties or application-{profile}.yml, and its values override the ones in the main file. A bean annotated with @Profile("dev") is only registered when the dev profile is active.

## Importing configuration

spring.config.import adds other files or sources, for example spring.config.import=optional:file:./local.yml. The optional prefix avoids a startup failure when the file does not exist. Properties from an imported file take precedence over the file that imports it.

## Order of sources

Command line arguments win over environment variables, which win over the application files packaged in the jar. Use that order to override a packaged value in production without rebuilding the image.
