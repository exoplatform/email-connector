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
package org.exoplatform.emailConnector.listener;

import static org.exoplatform.emailConnector.utils.EmailConnectorUtils.ACCESS_WEBMAIL;
import static org.exoplatform.emailConnector.utils.EmailConnectorUtils.OPEN_EMAIL;
import static org.exoplatform.emailConnector.utils.EmailConnectorUtils.SEND_EMAIL;
import static org.exoplatform.emailConnector.utils.EmailConnectorUtils.SEND_EMAIL_IN_OWNERS_NAME;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.analytics.model.StatisticData;
import io.meeds.analytics.utils.AnalyticsUtils;
import jakarta.annotation.PostConstruct;

@Asynchronous
@Component
@Profile("analytics")
public class AnalyticsEmailListener extends Listener<String, String> {

  private static final String   OPEN_EMAIL_OPERATION_NAME     = "openEmail";

  private static final String   SEND_EMAIL_OPERATION_NAME     = "sendEmail";

  private static final String   ACCESS_WEBMAIL_OPERATION_NAME = "accessWebmail";

  /** A mail sent in a shared mailbox owner's name (EXO-90583), counted by shape. */
  private static final String   SEND_EMAIL_IN_OWNERS_NAME_OPERATION_NAME = "sendEmailInOwnersName";

  private static final String[] LISTENER_EVENTS               = { OPEN_EMAIL, SEND_EMAIL, ACCESS_WEBMAIL, SEND_EMAIL_IN_OWNERS_NAME };

  @Autowired
  private IdentityManager       identityManager;

  @Autowired
  private ListenerService       listenerService;

  /**
   * Subscribes to the mail events the analytics count.
   */
  @PostConstruct
  public void init() {
    for (String listener : LISTENER_EVENTS) {
      listenerService.addListener(listener, this);
    }
  }

  /**
   * Records one mail event as a statistic of the user who caused it: the connector for
   * an opened mail or the webmail, the kind of mail for a send, and the shape for a mail
   * sent in a shared mailbox owner's name (EXO-90583) -- never the owner's address.
   *
   * @param event the event: source the user, data as each event defines it
   * @throws Exception when the statistic cannot be recorded
   */
  @Override
  public void onEvent(Event<String, String> event) throws Exception {
    String eventData = event.getData();
    String operation = mapEventNameToOperation(event.getEventName());
    long userId = 0;
    Identity identity = getIdentityManager().getOrCreateUserIdentity(event.getSource());
    if (identity != null) {
      userId = Long.parseLong(identity.getId());
    }
    StatisticData statisticData = new StatisticData();

    statisticData.setModule("email");
    statisticData.setSubModule("email");
    statisticData.setOperation(operation);
    statisticData.setUserId(userId);
    if (event.getEventName().equals(OPEN_EMAIL) || event.getEventName().equals(ACCESS_WEBMAIL)) {
      statisticData.addKeyword("connectorName", eventData);
    } else if (event.getEventName().equals(SEND_EMAIL)) {
      statisticData.addKeyword("emailType", eventData);
    } else if (event.getEventName().equals(SEND_EMAIL_IN_OWNERS_NAME)) {
      statisticData.addKeyword("sendMode", eventData);
    }
    AnalyticsUtils.addStatisticData(statisticData);
  }

  /**
   * The analytics operation of a mail event.
   *
   * @param eventName the event's name
   * @return the operation
   * @throws IllegalArgumentException for an event this listener does not subscribe to
   */
  private String mapEventNameToOperation(String eventName) {
    return switch (eventName) {
    case OPEN_EMAIL -> OPEN_EMAIL_OPERATION_NAME;
    case SEND_EMAIL -> SEND_EMAIL_OPERATION_NAME;
    case ACCESS_WEBMAIL -> ACCESS_WEBMAIL_OPERATION_NAME;
    case SEND_EMAIL_IN_OWNERS_NAME -> SEND_EMAIL_IN_OWNERS_NAME_OPERATION_NAME;
    default -> throw new IllegalArgumentException("Unknown event: " + eventName);
    };
  }

  /**
   * The identity manager, looked up from the container when it was not injected.
   *
   * @return the identity manager
   */
  public IdentityManager getIdentityManager() {
    if (identityManager == null) {
      identityManager = ExoContainerContext.getService(IdentityManager.class);
    }
    return identityManager;
  }
}
