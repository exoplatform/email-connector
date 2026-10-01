/**
 * Copyright (C) 2026 eXo Platform SAS
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
package org.exoplatform.emailConnector.service;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.CalendarLanding;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.plugin.InvitationCalendarPlugin;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Hands an answered invitation to the add-on holding the user's calendar
 * (EXO-90848): the {@link InvitationCalendarPlugin} beans of the platform, found by
 * type at the moment of the answer, in this WAR's Spring context -- into which the
 * bridge publishes every other WAR's exported beans -- and asked in turn until one
 * holds a calendar for the user.
 * <p>
 * Tolerant by construction: no implementer, a context that cannot be listed, or an
 * implementer whose classes cannot be linked all read as "no calendar for this user",
 * and the reader says nothing about a calendar. An implementer that holds the user's
 * calendar and refuses the invitation as it is ({@link IllegalArgumentException}) is
 * told to the user and not an incident; one that fails to update the calendar is
 * both, so the user knows their answer left and their calendar did not follow.
 */
@Service
public class InvitationLandingService {

  private static final Log         LOG = ExoLogger.getLogger(InvitationLandingService.class);

  private final ApplicationContext applicationContext;

  /**
   * @param applicationContext the Spring context of this WAR, where the other WARs'
   *          exported beans are published
   */
  @Autowired
  public InvitationLandingService(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * Lands the answered invitation in the user's calendar, when an add-on holds one.
   *
   * @param landing the invitation, the user and their answer
   * @return what became of it, null when no add-on holds a calendar for the user
   */
  public CalendarLanding land(InvitationLanding landing) {
    for (InvitationCalendarPlugin plugin : plugins()) {
      try {
        if (plugin.land(landing)) {
          LOG.debug("The invitation {} answered by user {} landed in their calendar through {}",
                    landing.uid(),
                    landing.username(),
                    plugin.getClass().getName());
          return CalendarLanding.LANDED;
        }
      } catch (LinkageError e) {
        // The add-on's classes cannot be linked: as if it were not installed.
        LOG.debug("Add-on {} could not be asked to land the invitation of user {}; it is read as holding no calendar",
                  plugin.getClass().getName(),
                  landing.username(),
                  e);
      } catch (IllegalArgumentException e) {
        // The sender's content, or a shape not landed yet: the user's to know, not an incident.
        LOG.debug("The invitation {} answered by user {} was not landed by {}: {}",
                  landing.uid(),
                  landing.username(),
                  plugin.getClass().getName(),
                  e.getMessage());
        return CalendarLanding.REFUSED;
      } catch (RuntimeException e) {
        LOG.warn("The invitation {} answered by user {} could not be landed in their calendar by {}",
                 landing.uid(),
                 landing.username(),
                 plugin.getClass().getName(),
                 e);
        return CalendarLanding.FAILED;
      }
    }
    return null;
  }

  /**
   * The add-ons that land invitations, looked up on every call: a type lookup Spring
   * caches, and the only way a contributor booting after this WAR is seen.
   *
   * @return the plugins, empty when there is none or they cannot be listed
   */
  private Collection<InvitationCalendarPlugin> plugins() {
    try {
      return applicationContext == null ? List.of() : applicationContext.getBeansOfType(InvitationCalendarPlugin.class).values();
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("The add-ons that land invitations in a calendar could not be listed; the answer is not landed", e);
      return List.of();
    }
  }
}
