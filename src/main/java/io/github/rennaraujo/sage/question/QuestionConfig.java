package io.github.rennaraujo.sage.question;

import io.github.rennaraujo.sage.audit.AuditLog;
import io.github.rennaraujo.sage.question.llm.LanguageModel;
import io.github.rennaraujo.sage.question.llm.ModelProperties;
import io.github.rennaraujo.sage.question.llm.PromptBuilder;
import io.github.rennaraujo.sage.search.RetrievalProperties;
import io.github.rennaraujo.sage.search.Retriever;
import io.github.rennaraujo.sage.search.SearchIndex;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QuestionProperties.class)
class QuestionConfig {

    @Bean
    PromptBuilder promptBuilder() {
        return new PromptBuilder();
    }

    /** O reranker existe ou não desde o boot, então o limiar pode ser fixado aqui. */
    @Bean
    RouteDecider routeDecider(SearchIndex index, RetrievalProperties properties, Retriever retriever) {
        return new RouteDecider(index, properties, retriever.rerankerActive());
    }

    @Bean
    AnswerService answerService(
            QuestionAnalyzer analyzer,
            AliasCatalog catalog,
            Retriever retriever,
            SearchIndex index,
            RouteDecider decider,
            PromptBuilder promptBuilder,
            LanguageModel languageModel,
            AuditLog audit,
            ModelProperties models) {
        return new AnswerService(
                analyzer,
                catalog,
                retriever,
                index,
                decider,
                promptBuilder,
                languageModel,
                audit,
                models,
                Clock.systemUTC());
    }
}
