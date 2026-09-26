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
package org.exoplatform.emailConnector.service.rules.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.exception.ForwardingRefusedException;
import org.exoplatform.emailConnector.model.ForwardingDestination;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRule.Action;
import org.exoplatform.emailConnector.model.ServerRule.Condition;

/**
 * eXo's forward in its own script, and a rule that forwards: always
 * {@code redirect :copy}, never a plain {@code redirect}, and only ever to a destination
 * the write was authorized for -- whatever the header holds, a header read back from the
 * server included.
 */
public class ExoSieveScriptForwardTest {

  private static final String BOB     = "bob@stalwart.local";

  private static final String EVIL    = "exfil@evil.example";

  private static final ManageSieveCapabilities WITH_COPY    =
                                                         new ManageSieveCapabilities("Stalwart",
                                                                                     Set.of("PLAIN"),
                                                                                     Set.of("copy", "fileinto", "imap4flags"),
                                                                                     true,
                                                                                     "1.0",
                                                                                     1);

  private static final ManageSieveCapabilities WITHOUT_COPY =
                                                         new ManageSieveCapabilities("Stalwart",
                                                                                     Set.of("PLAIN"),
                                                                                     Set.of("fileinto", "imap4flags"),
                                                                                     true,
                                                                                     "1.0",
                                                                                     1);

  /**
   * The forward's section, byte for byte: after the reply's place, before the rules,
   * {@code redirect :copy} to the authorized destination, {@code copy} required.
   */
  @Test
  public void testTheForwardIsARedirectCopy() {
    String text = ExoSieveScript.empty().withForward(BOB).withAuthorizedRedirects(Set.of(BOB)).toScript();
    String expected = "# exo-managed-v1: {\"v\":1,\"forward\":{\"to\":\"" + BOB + "\"},\"rules\":[]}\r\n"
        + "require [\"copy\"];\r\n"
        + "# exo-forward\r\n"
        + "redirect :copy \"" + BOB + "\";\r\n"
        + "# exo-rules\r\n";
    assertEquals(expected, text);
  }

  /**
   * The generator never writes a plain {@code redirect}: every {@code redirect} it emits,
   * the forward's and a rule's, carries {@code :copy}.
   */
  @Test
  public void testNoPlainRedirectIsEverWritten() {
    ExoSieveScript script = ExoSieveScript.empty()
                                          .withForward(BOB)
                                          .withRules(List.of(forwardRule("1", "carol@stalwart.local")))
                                          .withAuthorizedRedirects(Set.of(BOB, "carol@stalwart.local"));
    String text = script.toScript(WITH_COPY);
    long redirects = text.lines().filter(line -> line.trim().startsWith("redirect")).count();
    long copies = text.lines().filter(line -> line.trim().startsWith("redirect :copy ")).count();
    assertEquals(2, redirects);
    assertEquals(redirects, copies);
    assertEquals(2, script.redirectCount());
  }

  /**
   * A forward to a destination the write was not authorized for is refused by the
   * generator, even though the model holds it -- and nothing is generated.
   */
  @Test
  public void testAnUnauthorizedForwardIsRefused() {
    ExoSieveScript script = ExoSieveScript.empty().withForward(EVIL);
    assertEquals(ForwardingRefusedException.NOT_AUTHORIZED,
                 assertThrows(ForwardingRefusedException.class, script::toScript).getMessage());
    assertEquals(ForwardingRefusedException.NOT_AUTHORIZED,
                 assertThrows(ForwardingRefusedException.class,
                              () -> script.withAuthorizedRedirects(Set.of(BOB)).toScript(WITH_COPY)).getMessage());
  }

  /**
   * A rule that forwards to a destination the write was not authorized for is refused by
   * the generator: a rule read back from the server is never trusted to forward.
   */
  @Test
  public void testAnUncheckedRuleNeverForwards() {
    ExoSieveScript script = ExoSieveScript.empty().withRules(List.of(forwardRule("1", EVIL)));
    assertEquals(ForwardingRefusedException.NOT_AUTHORIZED,
                 assertThrows(ForwardingRefusedException.class, script::toScript).getMessage());
    assertEquals(ForwardingRefusedException.NOT_AUTHORIZED,
                 assertThrows(ForwardingRefusedException.class,
                              () -> script.withAuthorizedRedirects(Set.of(BOB)).toScript(WITH_COPY)).getMessage());
    // A disabled rule emits nothing, so it needs no authorization.
    ServerRule off = new ServerRule("2", "Off", false, true, List.of(fromAcme()), List.of(forward(EVIL)), false);
    assertFalse(ExoSieveScript.empty().withRules(List.of(off)).toScript().contains("redirect"));
  }

  /**
   * A header edited outside eXo to forward elsewhere reads back as eXo's model, and a
   * "Re-publish" from it is still refused: the authorized destinations are the caller's,
   * not the header's.
   */
  @Test
  public void testATamperedHeaderCannotForward() {
    String tampered = ExoSieveScript.empty().withForward(BOB).withAuthorizedRedirects(Set.of(BOB)).toScript().replace(BOB, EVIL);
    ExoSieveScript read = ExoSieveScript.parse(tampered).orElseThrow();
    assertEquals(EVIL, read.getForward().orElseThrow());
    assertThrows(ForwardingRefusedException.class, () -> read.withAuthorizedRedirects(Set.of(BOB)).toScript(WITH_COPY));
  }

  /**
   * A server that does not advertise {@code copy} gets no forward at all: a redirect
   * there would take the mail out of the mailbox.
   */
  @Test
  public void testNoCopyNoForward() {
    ExoSieveScript script = ExoSieveScript.empty().withForward(BOB).withAuthorizedRedirects(Set.of(BOB));
    assertEquals(ForwardingRefusedException.COPY_UNSUPPORTED,
                 assertThrows(ForwardingRefusedException.class, () -> script.toScript(WITHOUT_COPY)).getMessage());
    // A script that forwards nothing is generated there as before.
    assertFalse(ExoSieveScript.empty().toScript(WITHOUT_COPY).contains("copy"));
  }

  /**
   * A rule that forwards writes its copy before its filing, and requires {@code copy}
   * once, with the rules' own extensions.
   */
  @Test
  public void testARuleForwardsBeforeItFiles() {
    ServerRule rule = new ServerRule("1",
                                     "Invoices",
                                     true,
                                     true,
                                     List.of(fromAcme()),
                                     List.of(new Action(ServerRule.MOVE_TO_FOLDER, "CUSTOM:1", "Invoices", null), forward(BOB)),
                                     true);
    String text = ExoSieveScript.empty().withRules(List.of(rule)).withAuthorizedRedirects(Set.of(BOB)).toScript();
    assertTrue(text.contains("require [\"fileinto\", \"copy\"];\r\n"), text);
    assertTrue(text.indexOf("  redirect :copy \"" + BOB + "\";") < text.indexOf("  fileinto \"Invoices\";"), text);
    assertTrue(text.indexOf("  fileinto \"Invoices\";") < text.indexOf("  stop;"), text);
  }

  /**
   * The forward survives the header round trip, and the reply's or the rules' write
   * keeps it; a script without a forward is unchanged by the key's existence.
   */
  @Test
  public void testTheForwardRoundTrips() {
    ExoSieveScript script = ExoSieveScript.empty().withForward(BOB);
    ExoSieveScript read = ExoSieveScript.parse(script.withAuthorizedRedirects(Set.of(BOB)).toScript()).orElseThrow();
    assertEquals(script, read);
    assertEquals(BOB, read.withRules(List.of()).getForward().orElseThrow());
    assertFalse(ExoSieveScript.empty().toScript().contains("forward"));
    assertTrue(ExoSieveScript.parse(ExoSieveScript.empty().toScript()).orElseThrow().getForward().isEmpty());
  }

  /**
   * Only a plain address is a destination: nothing that could carry a second address, a
   * display name, a quote or a line break reaches the model.
   */
  @Test
  public void testOnlyAPlainAddressIsADestination() {
    for (String bad : List.of("", "bob", "bob@", "@x.org", "bob@x.org, eve@y.org", "Bob <bob@x.org>", "bob\"@x.org",
                              "bob@x.org\r\nredirect", "bob@x..org", ".bob@x.org", "bob.@x.org", "bob@-x.org")) {
      assertEquals(ForwardingDestination.INVALID,
                   assertThrows(IllegalArgumentException.class, () -> ExoSieveScript.empty().withForward(bad)).getMessage(),
                   bad);
    }
    assertEquals("bob.smith+exo@mail.example.org", ExoSieveScript.empty().withForward(" Bob.Smith+exo@Mail.Example.ORG ").getForward().orElseThrow());
    assertThrows(IllegalArgumentException.class, () -> forwardRule("3", "not an address").validated());
  }

  /**
   * A header whose forward is not a plain address is not eXo's to interpret.
   */
  @Test
  public void testAnInvalidForwardInTheHeaderIsUnreadable() {
    String text = ExoSieveScript.empty().withForward(BOB).withAuthorizedRedirects(Set.of(BOB)).toScript();
    assertTrue(ExoSieveScript.parse(text.replace("\"to\":\"" + BOB + "\"", "\"to\":\"a b@c\"")).isEmpty());
    assertTrue(ExoSieveScript.parse(text.replace("\"forward\":{\"to\":\"" + BOB + "\"}", "\"forward\":\"" + BOB + "\"")).isEmpty());
  }

  /**
   * How many redirects one run may execute: what the server advertises, one when it
   * does not say or says something that is not a number.
   */
  @Test
  public void testTheRedirectLimit() {
    assertEquals(3, ManageSieveCapabilities.fromLines(List.of(List.of("MAXREDIRECTS", "3"))).redirectLimit());
    assertEquals(1, ManageSieveCapabilities.fromLines(List.of(List.of("SIEVE", "copy"))).redirectLimit());
    assertEquals(1, ManageSieveCapabilities.fromLines(List.of(List.of("MAXREDIRECTS", "many"))).redirectLimit());
    assertEquals(0, ManageSieveCapabilities.fromLines(List.of(List.of("MAXREDIRECTS", "0"))).redirectLimit());
  }

  /**
   * A rule forwarding a copy to a destination.
   *
   * @param ref the reference
   * @param destination the destination
   * @return the rule
   */
  private static ServerRule forwardRule(String ref, String destination) {
    return new ServerRule(ref, "Forward " + ref, true, true, List.of(fromAcme()), List.of(forward(destination)), false);
  }

  /**
   * A forward action.
   *
   * @param destination the destination
   * @return the action
   */
  private static Action forward(String destination) {
    return new Action(ServerRule.FORWARD, null, null, null, destination);
  }

  /**
   * A condition on the sender's domain.
   *
   * @return the condition
   */
  private static Condition fromAcme() {
    return new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com");
  }
}
