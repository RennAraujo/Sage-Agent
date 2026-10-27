package io.github.rennaraujo.sage.chat;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serve a página do mini chat. O Spring Boot só trata o índice de {@code /} sozinho; para {@code /chat/} (e
 * {@code /chat}) é preciso apontar o {@code index.html} de forma explícita. Os arquivos estáticos em si
 * ({@code /chat/chat.js}, {@code /chat/chat.css}) saem do mapeamento padrão de recursos.
 */
@Configuration(proxyBeanMethods = false)
class ChatPageConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/chat").setViewName("redirect:/chat/");
        registry.addViewController("/chat/").setViewName("forward:/chat/index.html");
    }
}
