package com.soarer.alert.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class QueryLogsToolsConditionalTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QueryLogsTools.class);

    @Test
    void registersMockLogToolWhenMockIsEnabled() {
        contextRunner
                .withPropertyValues("cls.mock-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(QueryLogsTools.class));
    }

    @Test
    void doesNotRegisterMockLogToolWhenRealLogsAreEnabled() {
        contextRunner
                .withPropertyValues("cls.mock-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(QueryLogsTools.class));
    }
}
