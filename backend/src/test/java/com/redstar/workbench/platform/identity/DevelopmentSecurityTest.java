package com.redstar.workbench.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.redstar.workbench.organization.AuthorizationService;
import com.redstar.workbench.organization.IdentityProvider;
import com.redstar.workbench.organization.Permission;
import com.redstar.workbench.organization.UserIdentity;

class DevelopmentSecurityTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IdentityConfiguration.class)
            .withPropertyValues(
                    "redstar.identity.dev.user-id=" + USER_ID,
                    "redstar.identity.dev.organization-id=" + ORGANIZATION_ID,
                    "redstar.identity.dev.roles=ADMIN,REVIEWER");

    @Test
    void providesConfiguredIdentityAndAppliesAdminOnlyAuthorization() {
        contextRunner.run(context -> {
            var provider = context.getBean(IdentityProvider.class);
            var authorization = context.getBean(AuthorizationService.class);
            var permission = new Permission("system:read");

            assertThat(provider.currentIdentity())
                    .isEqualTo(new UserIdentity(USER_ID, ORGANIZATION_ID, Set.of("ADMIN", "REVIEWER")));
            assertThat(authorization.isAuthorized(provider.currentIdentity(), permission)).isTrue();

            var reviewer = new UserIdentity(USER_ID, ORGANIZATION_ID, Set.of("REVIEWER"));
            assertThat(authorization.isAuthorized(reviewer, permission)).isFalse();
        });
    }
}
