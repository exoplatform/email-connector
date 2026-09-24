/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.provider;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.UserEmailSettingService;
import org.exoplatform.services.connector.credentials.PersonalCredentialsProvider;
import org.exoplatform.services.connector.credentials.PersonalCredentialsSource;
import org.exoplatform.services.connector.credentials.RawCredentials;

/**
 * Exposes the email connector's own stored personal credentials
 * ({@code getEmailAddress()}/{@code getEmailPassword()} of {@link UserEmailSetting})
 * to the generic {@link org.exoplatform.services.connector.credentials.PersonalCredentialsProvider}.
 * <p>
 * Announces itself to {@link PersonalCredentialsProvider} from its own
 * {@code @PostConstruct} rather than waiting to be collected: this WAR's Spring
 * context is built after the provider's, so a {@code List<PersonalCredentialsSource>}
 * injected over there would be resolved before this bean existed - and would stay
 * empty, silently, since a missing source produces no credentials rather than an
 * error.
 * <p>
 * Two guards, both for this addon's own Spring test contexts rather than for a
 * platform without the credentials module: that module is a {@code provided}
 * prerequisite of this WAR, and {@link EmailCredentialsResolver} requires its
 * {@code ConnectorCredentialsService} bean outright, so the WAR does not start
 * without it. {@code @ConditionalOnClass} is kept as a belt-and-braces guard: no
 * classpath this module builds or tests on lacks the class today (a {@code provided}
 * artifact is on the module's own test classpath), and it costs nothing - evaluated
 * from bytecode metadata, so the class is never loaded and no
 * {@code NoClassDefFoundError} is risked. {@code @Autowired(required = false)} plus
 * the null check below cover the case
 * where the class is there but the provider bean is not, which is exactly what a
 * context built from this addon's beans alone looks like.
 */
@Component
@ConditionalOnClass(PersonalCredentialsSource.class)
public class EmailPersonalCredentialsSource implements PersonalCredentialsSource {

  /** Declared once, on the resolver: the Personal provider matches this source to it by that kind. */
  public static final String CONNECTOR_KIND = EmailCredentialsResolver.CONNECTOR_KIND;

  @Autowired(required = false)
  private PersonalCredentialsProvider personalCredentialsProvider;

  private final UserEmailSettingService userEmailSettingService;

  public EmailPersonalCredentialsSource(UserEmailSettingService userEmailSettingService) {
    this.userEmailSettingService = userEmailSettingService;
  }

  /**
   * Announces this source to the generic Personal provider, if that provider is
   * there at all.
   *
   * @see PersonalCredentialsProvider#register(PersonalCredentialsSource)
   */
  @PostConstruct
  public void register() {
    if (personalCredentialsProvider != null) {
      personalCredentialsProvider.register(this);
    }
  }

  @Override
  public String getConnectorKind() {
    return CONNECTOR_KIND;
  }

  /**
   * The mailbox's own address and password, read from the stored setting alone - a
   * {@code SettingService} cache read, no database read on a hit (see
   * {@link UserEmailSettingService#getStoredUserEmailSetting(String)}).
   */
  @Override
  public RawCredentials getCredentials(String username) {
    UserEmailSetting userEmailSetting = userEmailSettingService.getStoredUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null) {
      return null;
    }
    return new RawCredentials(userEmailSetting.getEmailAddress(), userEmailSetting.getEmailPassword());
  }

}
