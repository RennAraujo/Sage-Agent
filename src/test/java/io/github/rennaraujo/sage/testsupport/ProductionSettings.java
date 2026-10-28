package io.github.rennaraujo.sage.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Valores de {@code sage.*} do {@code application.yml} de produção, para os testes medirem o que vai ao ar. */
public final class ProductionSettings {

    private ProductionSettings() {}

    public static int integer(String section, String key) {
        Object value = section(section).get(key);
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new IllegalStateException("application.yml: sage." + section + "." + key + " ausente ou não inteira");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(String name) {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            Map<String, Object> sage = (Map<String, Object>) root.get("sage");
            Object section = sage.get(name);
            if (section instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new IllegalStateException("application.yml: seção sage." + name + " ausente");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
