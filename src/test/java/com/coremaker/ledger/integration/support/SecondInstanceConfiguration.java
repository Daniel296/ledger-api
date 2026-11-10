package com.coremaker.ledger.integration.support;

import java.util.Map;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import com.coremaker.ledger.LedgerApiApplication;

@TestConfiguration(proxyBeanMethods = false)
class SecondInstanceConfiguration {

    @Bean
    @Lazy
    SecondInstance secondInstance(JdbcConnectionDetails database) {

        var context = new SpringApplicationBuilder(LedgerApiApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(SecondInstanceConfiguration::skipTestConfigurations)
                .properties(Map.of(
                        "spring.datasource.url", database.getJdbcUrl(),
                        "spring.datasource.username", database.getUsername(),
                        "spring.datasource.password", database.getPassword()))
                .run();

        return new SecondInstance(context);
    }

    private static void skipTestConfigurations(ConfigurableApplicationContext context) {

        context.getBeanFactory().registerSingleton("skipTestConfigurations", new TypeExcludeFilter() {

            @Override
            public boolean match(MetadataReader reader, MetadataReaderFactory factory) {

                return reader.getAnnotationMetadata().hasAnnotation(TestConfiguration.class.getName());
            }
        });
    }
}
