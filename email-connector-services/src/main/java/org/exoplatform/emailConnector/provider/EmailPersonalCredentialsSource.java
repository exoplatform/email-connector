/**
 * Copyright (C) 2025 eXo Platform SAS.
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
 * ({@link UserEmailSetting#getEmailAddress()}/{@link UserEmailSetting#getEmailPassword()})
 * to the generic {@link org.exoplatform.services.connector.credentials.PersonalCredentialsProvider}.
 * <p>
 * Announces itself to {@link PersonalCredentialsProvider} from its own
 * {@code @PostConstruct} rather than waiting to be collected: this WAR's Spring
 * context is built after the provider's, so a {@code List<PersonalCredentialsSource>}
 * injected over there would be resolved before this bean existed - and would stay
 * empty, silently, since a missing source produces no credentials rather than an
 * error.
 * <p>
 * Two guards, for two different absences. {@code @ConditionalOnClass} keeps this
 * bean undefined when the credentials module is not on the classpath at all -
 * evaluated from bytecode metadata, so the class is never loaded and no
 * {@code NoClassDefFoundError} is risked. {@code @Autowired(required = false)}
 * plus the null check below cover the case where the class is there but the bean
 * is not, which is exactly this addon's own Spring test context.
 */
@Component
@ConditionalOnClass(PersonalCredentialsSource.class)
public class EmailPersonalCredentialsSource implements PersonalCredentialsSource {

  public static final String CONNECTOR_KIND = "email";

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

  @Override
  public RawCredentials getCredentials(String username) {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null) {
      return null;
    }
    return new RawCredentials(userEmailSetting.getEmailAddress(), userEmailSetting.getEmailPassword());
  }

}
