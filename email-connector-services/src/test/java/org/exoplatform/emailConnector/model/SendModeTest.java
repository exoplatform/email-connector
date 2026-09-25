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
package org.exoplatform.emailConnector.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * EXO-90582 -- the consent to writing in the owner's name and the administrator's
 * declaration: what each shape allows, how a stored or requested value reads, what a
 * connector declares, and the usable shapes the delegate's list and the send guard share.
 */
class SendModeTest {

  private static final long CONNECTOR_ID = 7L;

  /**
   * Puts the properties back the way the JVM had them.
   */
  @AfterEach
  void clearTheProperties() {
    System.clearProperty(SendMode.MODES_PROPERTY);
    System.clearProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID);
    System.clearProperty(SendMode.ENABLED_PROPERTY);
  }

  /**
   * AS covers both shapes, ON_BEHALF itself only, NONE nothing -- and nothing covers NONE.
   */
  @Test
  void asCoversBothShapesAndOnBehalfItselfOnly() {
    assertTrue(SendMode.AS.allows(SendMode.AS));
    assertTrue(SendMode.AS.allows(SendMode.ON_BEHALF), "the transparent shape is always available");
    assertTrue(SendMode.ON_BEHALF.allows(SendMode.ON_BEHALF));
    assertFalse(SendMode.ON_BEHALF.allows(SendMode.AS), "on behalf never widens to as");
    assertFalse(SendMode.NONE.allows(SendMode.ON_BEHALF));
    assertFalse(SendMode.AS.allows(SendMode.NONE));
    assertFalse(SendMode.AS.allows(null));
  }

  /**
   * A request's value is read leniently; an unknown one is none of them. NONE is stored
   * as null and a null, blank or unknown column reads as no consent.
   */
  @Test
  void valuesAreReadLenientlyAndNoneIsStoredAsNull() {
    assertEquals(SendMode.ON_BEHALF, SendMode.of(" on_behalf "));
    assertNull(SendMode.of("WHATEVER"));
    assertNull(SendMode.of(" "));
    assertNull(SendMode.NONE.stored());
    assertEquals("AS", SendMode.AS.stored());
    assertNull(SendMode.fromStored(null));
    assertNull(SendMode.fromStored("NONE"));
    assertNull(SendMode.fromStored("SOMETHING_NEWER"), "a value this version does not know is no consent");
    assertEquals(SendMode.AS, SendMode.fromStored("AS"));
  }

  /**
   * With nothing declared, a connector accepts on behalf only: it needs no server change.
   */
  @Test
  void onBehalfIsDeclaredByDefault() {
    assertEquals(Set.of(SendMode.ON_BEHALF), SendMode.declaredFor(CONNECTOR_ID));
    assertEquals(Set.of(SendMode.ON_BEHALF), SendMode.declaredFor(null));
  }

  /**
   * A connector's own declaration wins over the global one; as includes on behalf; none
   * declares nothing; a comma list is a union.
   */
  @Test
  void aConnectorsOwnDeclarationWinsAndAsIncludesOnBehalf() {
    System.setProperty(SendMode.MODES_PROPERTY, "none");
    assertEquals(Set.of(), SendMode.declaredFor(CONNECTOR_ID));
    System.setProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID, "As");
    assertEquals(Set.of(SendMode.ON_BEHALF, SendMode.AS), SendMode.declaredFor(CONNECTOR_ID));
    assertEquals(Set.of(), SendMode.declaredFor(8L), "another connector follows the global declaration");
    System.setProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID, "onBehalf, none");
    assertEquals(Set.of(SendMode.ON_BEHALF), SendMode.declaredFor(CONNECTOR_ID));
  }

  /**
   * A declaration that cannot be read declares nothing: it never widens.
   */
  @Test
  void anUnreadableDeclarationDeclaresNothing() {
    System.setProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID, "sendAs");
    assertEquals(Set.of(), SendMode.declaredFor(CONNECTOR_ID));
  }

  /**
   * The kill switch off declares nothing anywhere, whatever the connectors say.
   */
  @Test
  void theKillSwitchDeclaresNothing() {
    System.setProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID, "as");
    System.setProperty(SendMode.ENABLED_PROPERTY, " FALSE ");
    assertFalse(SendMode.isEnabled());
    assertEquals(Set.of(), SendMode.declaredFor(CONNECTOR_ID));
    System.setProperty(SendMode.ENABLED_PROPERTY, "true");
    assertTrue(SendMode.isEnabled());
  }

  /**
   * The usable shapes: the consent narrowed to the declaration, never widened past the
   * consent, and none with no consent at all.
   */
  @Test
  void theUsableShapesAreTheConsentNarrowedToTheDeclaration() {
    Set<SendMode> both = Set.of(SendMode.ON_BEHALF, SendMode.AS);
    Set<SendMode> onBehalf = Set.of(SendMode.ON_BEHALF);
    assertEquals(List.of(SendMode.ON_BEHALF, SendMode.AS), SendMode.usable(SendMode.AS, null, null, both));
    assertEquals(List.of(SendMode.ON_BEHALF), SendMode.usable(SendMode.AS, null, null, onBehalf), "as on an on-behalf connector");
    assertEquals(List.of(SendMode.ON_BEHALF), SendMode.usable(SendMode.ON_BEHALF, null, null, both), "never wider than the consent");
    assertEquals(List.of(), SendMode.usable(null, null, null, both));
    assertEquals(List.of(), SendMode.usable(SendMode.AS, null, null, Set.of()));
  }

  /**
   * EXO-90626 -- a server's refusal drops the shape it refused and the wider ones, and
   * nothing narrower: as the owner refused leaves on her behalf; on her behalf refused
   * leaves nothing; a refusal that named no shape (recorded before the shape was) leaves
   * nothing, as it did then; a refusal shape without a date refuses nothing.
   */
  @Test
  void aRefusalDropsItsShapeAndTheWiderOnesOnly() {
    Set<SendMode> both = Set.of(SendMode.ON_BEHALF, SendMode.AS);
    Date refused = new Date();
    assertEquals(List.of(SendMode.ON_BEHALF), SendMode.usable(SendMode.AS, refused, SendMode.AS, both),
                 "as refused: on her behalf still works");
    assertEquals(List.of(), SendMode.usable(SendMode.AS, refused, SendMode.ON_BEHALF, both), "on her behalf refused: both go");
    assertEquals(List.of(), SendMode.usable(SendMode.AS, refused, null, both), "a refusal naming no shape: both go");
    assertEquals(List.of(SendMode.ON_BEHALF), SendMode.usable(SendMode.ON_BEHALF, refused, SendMode.AS, both),
                 "an on-behalf consent is untouched by a refusal as her");
    assertEquals(List.of(), SendMode.usable(SendMode.ON_BEHALF, refused, SendMode.ON_BEHALF, both));
    assertEquals(List.of(SendMode.ON_BEHALF, SendMode.AS), SendMode.usable(SendMode.AS, null, SendMode.AS, both),
                 "no date, no refusal");

    for (SendMode requested : List.of(SendMode.ON_BEHALF, SendMode.AS)) {
      assertFalse(SendMode.refusedByServer(requested, null, SendMode.ON_BEHALF), requested + " never refused without a date");
      assertTrue(SendMode.refusedByServer(requested, refused, SendMode.ON_BEHALF), requested + " after a refusal on her behalf");
      assertTrue(SendMode.refusedByServer(requested, refused, null), requested + " after a refusal naming no shape");
    }
    assertTrue(SendMode.refusedByServer(SendMode.AS, refused, SendMode.AS));
    assertFalse(SendMode.refusedByServer(SendMode.ON_BEHALF, refused, SendMode.AS), "the narrower shape stays");
  }
}
