/**
 * Copyright (C) 2025 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.provider;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsProvider;
import org.exoplatform.services.connector.credentials.PersonalCredentialsProvider;
import org.exoplatform.services.connector.credentials.PersonalCredentialsSource;

/**
 * Declares the shared {@link PersonalCredentialsProvider} bean. Guarded with
 * {@link ConditionalOnMissingBean} rather than owned outright by this add-on: any
 * other connector wanting Personal mode declares the exact same bean method in its
 * own module, so the provider's existence never depends on this specific add-on
 * being installed - only on at least one Personal-mode connector being present.
 */
@Configuration
public class PersonalCredentialsProviderConfiguration {

  @Bean
  @ConditionalOnMissingBean(PersonalCredentialsProvider.class)
  public ConnectorCredentialsProvider personalCredentialsProvider(List<PersonalCredentialsSource> sources) {
    return new PersonalCredentialsProvider(sources);
  }

}
