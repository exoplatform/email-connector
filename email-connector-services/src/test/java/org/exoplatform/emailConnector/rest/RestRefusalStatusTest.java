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

package org.exoplatform.emailConnector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.exception.SendModeMissingException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SendMode;
import org.exoplatform.emailConnector.rest.model.EmailManagedModeRequest;
import org.exoplatform.emailConnector.rest.model.ScheduleRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * No refusal the services raise is answered 401 by any endpoint of the add-on, and every
 * endpoint that lets a refusal surface as a status answers 403 (EXO-90627,
 * {@code backend-spring.md} §5: an {@link IllegalAccessException} is a refusal). Two
 * handlers answer a refusal otherwise on purpose, and are not gaps: the favorite read
 * answers 404, so the id is not confirmed, and the cached search answers an empty page,
 * since most users have no mailbox connected. No endpoint of the add-on answers 401 at all: a caller who is not
 * signed in is turned away before any handler runs, by the platform's web security,
 * whose entry point answers 403 too.
 * <p>
 * A sweep rather than one test per endpoint: each controller is built with services
 * that throw the refusal from every method declaring it, and every request handler is
 * called once. No handler may answer 401, and at least a floor of handlers per
 * controller must answer 403 -- one catch left at 401, or moved to another status,
 * anywhere fails the sweep. The floors
 * keep the sweep from passing vacuously (a handler that never reaches its service
 * proves nothing): they are the number of handlers of each controller that reach a
 * refusing service, and a new handler that answers a refusal only raises them.
 */
class RestRefusalStatusTest {

  private static final String                               USER   = "john";

  /**
   * The handlers per controller that must answer 403 to a refusal, at least: the number
   * of its handlers that reach a refusing service.
   */
  private static final Map<Class<?>, Integer>               FLOORS = Map.of(EmailBoxRest.class, 48,
                                                                            UserEmailSettingRest.class, 16,
                                                                            EmailConnectorRest.class, 18,
                                                                            EmailContactRest.class, 1,
                                                                            EmailFilterRest.class, 6);

  /**
   * A plain refusal -- no connected mailbox, not an administrator, not the owner --
   * answers 403 on at least the floor's number of handlers of each controller, and 401 on
   * none.
   *
   * @throws Exception when a controller cannot be built
   */
  @Test
  void aPlainRefusalAnswersForbiddenEverywhere() throws Exception {
    for (Map.Entry<Class<?>, Integer> controller : FLOORS.entrySet()) {
      assertForbidden(controller.getKey(), controller.getValue(), sweep(controller.getKey(), () -> new IllegalAccessException("refused")));
    }
  }

  /**
   * A right missing in a shared folder, and a consent that does not cover the name a
   * mail goes out in, are refusals too: 403 wherever they surface, on as many handlers
   * as a plain refusal -- the handlers that name the missing right or shape in the
   * reason, and the ones that fall back to the plain refusal alike.
   *
   * @throws Exception when a controller cannot be built
   */
  @Test
  void aDelegationOrConsentRefusalAnswersForbiddenEverywhere() throws Exception {
    List<Supplier<IllegalAccessException>> refusals = List.of(() -> new MailboxRightMissingException(MailboxRights.KEEP_SEEN),
                                                              () -> new SendModeMissingException(SendMode.AS));
    for (Supplier<IllegalAccessException> refusal : refusals) {
      for (Map.Entry<Class<?>, Integer> controller : FLOORS.entrySet()) {
        assertForbidden(controller.getKey(), controller.getValue(), sweep(controller.getKey(), refusal));
      }
    }
  }

  /**
   * Fails when any handler answered 401, or fewer handlers than the floor answered 403.
   *
   * @param controller the controller swept
   * @param floor the handlers that must answer 403, at least
   * @param statuses the status each handler answered, by handler name
   */
  private static void assertForbidden(Class<?> controller, int floor, Map<String, Integer> statuses) {
    assertNoUnauthorized(controller, statuses);
    long forbidden = statuses.values().stream().filter(status -> status == 403).count();
    assertTrue(forbidden >= floor,
               controller.getSimpleName() + ": only " + forbidden + " handlers answered 403 to a refusal (floor " + floor + "): "
                   + statuses);
  }

  /**
   * Fails when any handler answered 401.
   *
   * @param controller the controller swept
   * @param statuses the status each handler answered, by handler name
   */
  private static void assertNoUnauthorized(Class<?> controller, Map<String, Integer> statuses) {
    List<String> unauthorized = statuses.entrySet()
                                        .stream()
                                        .filter(entry -> entry.getValue() == 401)
                                        .map(Map.Entry::getKey)
                                        .toList();
    assertEquals(List.of(), unauthorized, controller.getSimpleName() + ": a refusal answered 401");
  }

  /**
   * Builds the controller with refusing services and calls each of its request
   * handlers once.
   *
   * @param controllerType the controller class
   * @param refusal the refusal the services throw
   * @return the status each handler answered with a {@link ResponseStatusException}, by
   *         handler name; handlers that answered otherwise are absent
   * @throws Exception when the controller cannot be built
   */
  private static Map<String, Integer> sweep(Class<?> controllerType,
                                            Supplier<IllegalAccessException> refusal) throws Exception {
    Constructor<?> constructor = controllerType.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object controller = constructor.newInstance();
    for (Field field : controllerType.getDeclaredFields()) {
      if (field.isAnnotationPresent(Autowired.class)) {
        field.setAccessible(true);
        field.set(controller, mock(field.getType(), new Refusing(refusal)));
      }
    }
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteUser()).thenReturn(USER);
    Map<String, Integer> statuses = new TreeMap<>();
    for (Method handler : controllerType.getDeclaredMethods()) {
      if (!Modifier.isPublic(handler.getModifiers()) || !isHandler(handler)) {
        continue;
      }
      Object[] arguments = new Object[handler.getParameterCount()];
      Class<?>[] types = handler.getParameterTypes();
      for (int i = 0; i < types.length; i++) {
        arguments[i] = HttpServletRequest.class.isAssignableFrom(types[i]) ? request : sample(types[i]);
      }
      try {
        handler.invoke(controller, arguments);
      } catch (InvocationTargetException e) {
        if (e.getCause() instanceof ResponseStatusException status) {
          statuses.put(handler.getName(), status.getStatusCode().value());
        }
      }
    }
    return statuses;
  }

  /**
   * @param method a controller method
   * @return true when it serves a request
   */
  private static boolean isHandler(Method method) {
    return method.isAnnotationPresent(GetMapping.class) || method.isAnnotationPresent(PostMapping.class)
        || method.isAnnotationPresent(PutMapping.class) || method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class);
  }

  /**
   * A plausible, non-null value of a handler parameter, so a handler gets past its own
   * argument checks to its service where it can.
   *
   * @param type the parameter type
   * @return a value of that type, or null when none can be made
   */
  private static Object sample(Class<?> type) {
    if (type == Email.class) {
      return aMail();
    } else if (type == EmailManagedModeRequest.class) {
      return new EmailManagedModeRequest(1L, List.of());
    } else if (type == ScheduleRequest.class) {
      return new ScheduleRequest(aMail(), 1_900_000_000_000L, "UTC");
    } else if (type == String.class) {
      return "1";
    } else if (type == long.class || type == Long.class) {
      return 1L;
    } else if (type == int.class || type == Integer.class) {
      return 1;
    } else if (type == boolean.class || type == Boolean.class) {
      return Boolean.TRUE;
    } else if (type == double.class || type == Double.class) {
      return 1d;
    } else if (List.class.isAssignableFrom(type) || Collection.class == type) {
      return new ArrayList<>(List.of("1"));
    } else if (Set.class.isAssignableFrom(type)) {
      return Set.of("1");
    } else if (Map.class.isAssignableFrom(type)) {
      return new TreeMap<>();
    } else if (type.isEnum()) {
      Object[] constants = type.getEnumConstants();
      return constants.length > 0 ? constants[0] : null;
    } else if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
      return mock(type);
    }
    try {
      Constructor<?> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /**
   * A mail with one recipient: the send and schedule handlers turn away a mail with
   * none before they reach their service.
   *
   * @return the mail
   */
  private static Email aMail() {
    Email mail = new Email();
    mail.setTo(new ArrayList<>(List.of(new EmailRecipient())));
    return mail;
  }

  /**
   * A service answer that throws the refusal from every method declaring
   * {@link IllegalAccessException} (or a supertype of it), and answers the Mockito
   * default everywhere else -- so the refusal only travels where the real service could
   * raise it.
   */
  private static final class Refusing implements Answer<Object> {

    private final Supplier<IllegalAccessException> refusal;

    /**
     * @param refusal the refusal to throw
     */
    private Refusing(Supplier<IllegalAccessException> refusal) {
      this.refusal = refusal;
    }

    /**
     * Throws the refusal when the invoked method declares it, else answers the default.
     *
     * @param invocation the service call
     * @return the Mockito default answer
     * @throws Throwable the refusal
     */
    @Override
    public Object answer(InvocationOnMock invocation) throws Throwable {
      for (Class<?> declared : invocation.getMethod().getExceptionTypes()) {
        if (declared.isAssignableFrom(IllegalAccessException.class)) {
          throw refusal.get();
        }
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    }
  }
}
