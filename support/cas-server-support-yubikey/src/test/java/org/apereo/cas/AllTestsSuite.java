
package org.apereo.cas;

import org.apereo.cas.adaptors.yubikey.JsonYubiKeyAccountRegistryTests;
import org.apereo.cas.adaptors.yubikey.YubiKeyAuthenticationHandlerTests;
import org.apereo.cas.adaptors.yubikey.YubiKeyConfigurationTests;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * This is {@link AllTestsSuite}.
 *
 * @author Auto-generated by Gradle Build
 * @since 6.0.0-RC3
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    JsonYubiKeyAccountRegistryTests.class,
    YubiKeyConfigurationTests.class,
    YubiKeyAuthenticationHandlerTests.class
})
public class AllTestsSuite {
}
