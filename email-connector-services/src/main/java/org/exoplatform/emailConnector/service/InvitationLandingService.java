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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.emailConnector.model.CalendarInvitation;
import org.exoplatform.emailConnector.model.CalendarLanding;
import org.exoplatform.emailConnector.model.HeldInvitation;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.model.InvitationProbe;
import org.exoplatform.emailConnector.model.LandedInvitation;
import org.exoplatform.emailConnector.plugin.InvitationCalendarPlugin;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import jakarta.annotation.PreDestroy;

/**
 * Hands an invitation to the add-on holding the user's calendar (EXO-90848): the
 * {@link InvitationCalendarPlugin} beans of the platform, found by type at the moment
 * of the click, in this WAR's Spring context -- into which the bridge publishes every
 * other WAR's exported beans -- and the first that holds a calendar for the user is
 * the one asked to land, alone: what it answers, nothing included, is the outcome.
 * <p>
 * Tolerant by construction: no implementer, a context that cannot be listed, or an
 * implementer whose classes cannot be linked all read as "no calendar for this user",
 * and the reader says nothing about a calendar. An implementer that holds the user's
 * calendar and refuses the invitation as it is ({@link IllegalArgumentException}) is
 * told to the user and not an incident; one that fails to update the calendar is
 * both, so the user knows their answer left and their calendar did not follow.
 * <p>
 * <b>Whether the calendar holds an invitation already</b> ({@link #held}, EXO-90873) is
 * asked when the reader shows one, so it is bounded: the add-on is asked on a small
 * executor of this service and waited for {@link #HELD_TIMEOUT_PROPERTY} at most; a late
 * answer, a full executor, a failure or an add-on that cannot be linked all read as
 * "nothing held", and the reader shows the invitation as it did before.
 */
@Service
public class InvitationLandingService {

  /** How long, in milliseconds, the reader waits for the add-on to say whether it holds an invitation. */
  public static final String       HELD_TIMEOUT_PROPERTY = "email.connector.invitation.heldTimeoutMillis";

  /** The default of {@link #HELD_TIMEOUT_PROPERTY}: three seconds. */
  public static final long         DEFAULT_HELD_TIMEOUT  = 3000L;

  /** The longest wait an administrator may set: thirty seconds. */
  static final long                MAX_HELD_TIMEOUT      = 30000L;

  /** Questions waiting for a thread: beyond this, the reader shows the invitation without asking. */
  static final int                 HELD_QUEUE_DEPTH      = 32;

  /** The most lookups running at once; two run while the queue is not full. */
  private static final int         HELD_MAX_THREADS      = 4;

  /** How long, in seconds, a thread beyond the first two waits idle before it ends. */
  private static final long        HELD_IDLE_SECONDS     = 60L;

  private static final Log         LOG                   = ExoLogger.getLogger(InvitationLandingService.class);

  private final ApplicationContext applicationContext;

  /** Asks the add-ons whether they hold an invitation, off the reader's thread so the wait is bounded. */
  private ExecutorService          heldExecutor          = newHeldExecutor();

  /**
   * @param applicationContext the Spring context of this WAR, where the other WARs'
   *          exported beans are published
   */
  @Autowired
  public InvitationLandingService(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * Whether an add-on holds a calendar for the user, which decides whether the reader
   * offers to add an invitation to it. No round trip: the implementers promise none.
   *
   * @param username the user
   * @return true when one does
   */
  public boolean holdsCalendarFor(String username) {
    for (InvitationCalendarPlugin plugin : plugins()) {
      try {
        if (plugin.holdsCalendarFor(username)) {
          return true;
        }
      } catch (RuntimeException | LinkageError e) {
        LOG.debug("Add-on {} could not say whether it holds a calendar for user {}; it is read as holding none",
                  plugin.getClass().getName(),
                  username,
                  e);
      }
    }
    return false;
  }

  /**
   * Lands the invitation in the user's calendar, when an add-on holds one, and tells
   * the reader what became of it.
   *
   * @param landing the invitation, the user and what they asked
   * @param invitation the invitation the reader shows, told the outcome and the link
   */
  public void land(InvitationLanding landing, CalendarInvitation invitation) {
    for (InvitationCalendarPlugin plugin : plugins()) {
      try {
        if (!plugin.holdsCalendarFor(landing.username())) {
          continue;
        }
        // The one add-on holding the user's calendar: nothing from it means
        // nothing to do, and no other add-on is asked.
        LandedInvitation landed = plugin.land(landing);
        if (landed != null) {
          LOG.debug("The invitation {} of user {} {} their calendar through {}",
                    landing.uid(),
                    landing.username(),
                    landed.removed() ? "was removed from" : "landed in",
                    plugin.getClass().getName());
          invitation.setLanding(landingOf(landed, landing.answer()));
          invitation.setLandingLink(landed.removed() ? null : landed.link());
          // The click was honoured: the card does not offer it again.
          invitation.setLandable(invitation.isLandable() && landed.removed());
          invitation.setRemovable(invitation.isRemovable() && !landed.removed());
          heldAfter(landed, landing.answer(), invitation);
        }
        return;
      } catch (LinkageError e) {
        // The add-on's classes cannot be linked: as if it were not installed.
        LOG.debug("Add-on {} could not be asked to land the invitation of user {}; it is read as holding no calendar",
                  plugin.getClass().getName(),
                  landing.username(),
                  e);
      } catch (IllegalArgumentException e) {
        // The sender's content, or a shape not landed yet: the user's to know, not an incident.
        LOG.debug("The invitation {} of user {} was not landed by {}: {}",
                  landing.uid(),
                  landing.username(),
                  plugin.getClass().getName(),
                  e.getMessage());
        invitation.setLanding(CalendarLanding.REFUSED);
        return;
      } catch (RuntimeException e) {
        LOG.warn("The invitation {} of user {} could not be landed in their calendar by {}",
                 landing.uid(),
                 landing.username(),
                 plugin.getClass().getName(),
                 e);
        invitation.setLanding(CalendarLanding.FAILED);
        return;
      }
    }
  }

  /**
   * Whether the user's calendar already holds the event an invitation is about, asked of
   * the add-on holding that calendar -- the first that says it holds one, alone, as for
   * a landing -- and waited for {@link #heldTimeout()} at most. Never throws: anything
   * but an answer in time is "nothing held".
   *
   * @param probe the user and the event's UID
   * @return the held copy, or null when nothing is held or nothing could be said in time
   */
  public HeldInvitation held(InvitationProbe probe) {
    ExoContainer container = currentContainer();
    Future<HeldInvitation> answer;
    try {
      answer = heldExecutor.submit(() -> askHeld(probe, container));
    } catch (RejectedExecutionException e) {
      LOG.debug("Too many invitations being looked up; the invitation {} of user {} is shown as not held", probe.uid(), probe.username());
      return null;
    }
    try {
      return answer.get(heldTimeout(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      answer.cancel(true);
      LOG.warn("The calendar of user {} did not say within {} ms whether it holds the invitation {}; it is shown as not held",
               probe.username(),
               heldTimeout(),
               probe.uid());
      return null;
    } catch (ExecutionException e) {
      LOG.warn("The calendar of user {} could not say whether it holds the invitation {}; it is shown as not held",
               probe.username(),
               probe.uid(),
               e.getCause());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      answer.cancel(true);
      return null;
    }
  }

  /**
   * Stops the lookups still running when the context is torn down; the readers waiting
   * on them stop waiting at their timeout.
   */
  @PreDestroy
  public void stop() {
    heldExecutor.shutdownNow();
  }

  /**
   * Asks the add-on holding the user's calendar whether it holds the invitation, on the
   * executor's thread, bound to the reader's container for the add-on's reads. An
   * add-on whose classes cannot be linked is as if it were not installed; any other
   * failure is the caller's to log.
   *
   * @param probe the user and the event's UID
   * @param container the reader's container, null when it has none
   * @return the held copy, or null
   */
  HeldInvitation askHeld(InvitationProbe probe, ExoContainer container) {
    if (container != null) {
      ExoContainerContext.setCurrentContainer(container);
      RequestLifeCycle.begin(container);
    }
    try {
      for (InvitationCalendarPlugin plugin : plugins()) {
        try {
          if (plugin.holdsCalendarFor(probe.username())) {
            // The one add-on holding the user's calendar: no other is asked.
            return plugin.held(probe);
          }
        } catch (LinkageError e) {
          LOG.debug("Add-on {} could not be asked whether it holds the invitation of user {}; it is read as holding no calendar",
                    plugin.getClass().getName(),
                    probe.username(),
                    e);
        }
      }
      return null;
    } finally {
      if (container != null) {
        RequestLifeCycle.end();
        ExoContainerContext.setCurrentContainer(null);
      }
    }
  }

  /**
   * How long the reader waits for the add-on ({@link #HELD_TIMEOUT_PROPERTY}), read at
   * every use.
   *
   * @return the wait in milliseconds, the default when the property is not a positive
   *         number, at most {@link #MAX_HELD_TIMEOUT}
   */
  static long heldTimeout() {
    try {
      long value = Long.parseLong(StringUtils.trim(System.getProperty(HELD_TIMEOUT_PROPERTY, String.valueOf(DEFAULT_HELD_TIMEOUT))));
      return value > 0 ? Math.min(value, MAX_HELD_TIMEOUT) : DEFAULT_HELD_TIMEOUT;
    } catch (NumberFormatException e) {
      return DEFAULT_HELD_TIMEOUT;
    }
  }

  /**
   * What the card says the calendar holds once a click landed or removed the event: the
   * copy just written, with the answer it carries -- the one given, or the one held
   * before when the click gave none -- or nothing once removed. An event of the
   * platform's own, which nothing was written for, changes nothing.
   *
   * @param landed what the add-on did
   * @param answer the answer given, null for an addition or a removal
   * @param invitation the invitation the reader shows
   */
  private static void heldAfter(LandedInvitation landed, InvitationAnswer answer, CalendarInvitation invitation) {
    if (landed.alreadyHeld()) {
      return;
    }
    if (landed.removed()) {
      invitation.setHeld(false);
      invitation.setHeldResponse(null);
      invitation.setHeldLink(null);
    } else {
      invitation.setHeld(true);
      invitation.setHeldLink(landed.link());
      if (answer != null) {
        invitation.setHeldResponse(answer);
      }
    }
    invitation.setNewerRevision(false);
  }

  /**
   * The reader's container, to bind on the executor's thread; never creates one.
   *
   * @return the container, null when the reader has none bound
   */
  private static ExoContainer currentContainer() {
    try {
      return ExoContainerContext.getCurrentContainerIfPresent();
    } catch (RuntimeException | LinkageError e) {
      LOG.debug("No container is bound to the reader's thread; the add-on is asked without one", e);
      return null;
    }
  }

  /**
   * A small pool of daemon threads, and a short queue: a reader never waits for a
   * thread, it shows the invitation as not held.
   *
   * @return the executor
   */
  private static ExecutorService newHeldExecutor() {
    return new ThreadPoolExecutor(2, HELD_MAX_THREADS, HELD_IDLE_SECONDS, TimeUnit.SECONDS, new ArrayBlockingQueue<>(HELD_QUEUE_DEPTH), runnable -> {
      Thread thread = new Thread(runnable, "email-invitation-held");
      thread.setDaemon(true);
      return thread;
    });
  }

  /**
   * For the tests: the executor the add-ons are asked on.
   *
   * @param executor the executor
   */
  void setHeldExecutor(ExecutorService executor) {
    this.heldExecutor.shutdownNow();
    this.heldExecutor = executor;
  }

  /**
   * What the reader tells of a landing: removed, already held, declined on a copy the
   * user held -- the add-on sets a decline on a copy and creates none, so what it
   * holds is a declined event, not an addition -- or landed.
   *
   * @param landed what the add-on did
   * @param answer the answer given, null for an addition or a removal
   * @return the outcome
   */
  private static CalendarLanding landingOf(LandedInvitation landed, InvitationAnswer answer) {
    if (landed.removed()) {
      return CalendarLanding.REMOVED;
    }
    if (landed.alreadyHeld()) {
      return CalendarLanding.ALREADY_HELD;
    }
    return answer == InvitationAnswer.DECLINED ? CalendarLanding.DECLINED : CalendarLanding.LANDED;
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
